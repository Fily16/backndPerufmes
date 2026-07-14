package org.example.backendbvaberiaperfumes.service.allocation;

import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.SupplierConstraintRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.PricingService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Motor de asignacion de compra v2.
 *
 * Responde la pregunta que el greedy anterior no podia responder:
 * ¿FORZAR el minimo de un proveedor (moviendo lineas caras hacia el) o SALTARLO
 * este ciclo (comprando todo en otro lado, con relleno de tienda o venta perdida)?
 *
 * Metodo: baseline al mas barato ELEGIBLE (respetando el piso de margen), luego
 * enumeracion exacta de 2^k subconjuntos de proveedores-con-minimo-insatisfecho
 * (k es 1-3 en la practica), con costos penalizados por relleno de tienda y venta
 * perdida; al final una pasada 1-opt. Las restricciones son datos (SupplierConstraint)
 * evaluados por ConstraintEvaluator plugables.
 */
@Service
public class AllocationOptimizer {

    /** Tope de proveedores restringidos a enumerar (2^4=16 planes; mas es config rota). */
    private static final int MAX_ENUM = 4;
    private static final int ONE_OPT_ITERATIONS = 200;

    private final SupplierRepository supplierRepo;
    private final SupplierOfferRepository offerRepo;
    private final SupplierConstraintRepository constraintRepo;
    private final ConstraintRegistry registry;
    private final PricingService pricing;

    public AllocationOptimizer(SupplierRepository supplierRepo,
                               SupplierOfferRepository offerRepo,
                               SupplierConstraintRepository constraintRepo,
                               ConstraintRegistry registry,
                               PricingService pricing) {
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.constraintRepo = constraintRepo;
        this.registry = registry;
        this.pricing = pricing;
    }

    // =====================================================================
    // Modelo interno
    // =====================================================================

    public static class Line {
        public Product product;
        public int qty;
        /** supplierId -> costo unitario USD (solo ofertas en stock de proveedores activos). */
        public Map<Long, Double> offers = new LinkedHashMap<>();
        /** supplierId -> margen unitario PEN (precio de venta - costo puesto en Peru). Null sin precio. */
        public Map<Long, Double> marginPen = new LinkedHashMap<>();
        /** Ofertas que respetan el piso de margen. Si ninguna lo respeta, todas son "elegibles con aviso". */
        public Set<Long> eligible = new LinkedHashSet<>();
        public Double avgUnitPricePen;      // precio promedio ya cobrado a clientes (snapshot)
        public Long baselineSupplierId;
        public double baselineUnitCost;
        public boolean marginWarning;       // ni la oferta mas barata respeta el piso
    }

    public static class SupplierDecision {
        public Long supplierId;
        public String supplierName;
        public double forceTotalUsd = Double.NaN;   // mejor costo total FORZANDO su minimo
        public double skipTotalUsd = Double.NaN;    // mejor costo total SALTANDOLO
        public String decision;                     // FORZAR | SALTAR
    }

    public static class LostSale {
        public Long productId;
        public String name;
        public int qty;
        public String reason;
    }

    public static class StoreFillNeed {
        public Long supplierId;
        public String supplierName;
        public String constraintType;
        public double remainingDeficit;   // en unidades del tipo (USD o unidades)
    }

    public static class MarginWarning {
        public Long productId;
        public String name;
        public Long supplierId;
        public String supplierName;
        public double marginPen;
        public double floorPen;
    }

    public static class Result {
        public List<Line> lines = new ArrayList<>();
        /** Asignacion final: index de linea -> supplierId (null = venta perdida). */
        public Map<Integer, Long> assignment = new LinkedHashMap<>();
        public double baselineTotalUsd;
        public double chosenTotalUsd;       // solo costo de lineas asignadas
        public double penaltiesUsd;         // relleno + ventas perdidas (contable)
        public List<SupplierDecision> skipAnalysis = new ArrayList<>();
        public List<LostSale> lostSales = new ArrayList<>();
        public List<StoreFillNeed> storeFillNeeds = new ArrayList<>();
        public List<MarginWarning> marginWarnings = new ArrayList<>();
        public Map<Long, Supplier> suppliersById = new LinkedHashMap<>();
        public List<Long> unfulfillableProductIds = new ArrayList<>();
    }

    // =====================================================================

    /**
     * @param demand           productId -> unidades pedidas (demanda activa del consolidado)
     * @param avgPriceByProduct productId -> precio unitario PEN promedio ya cobrado (snapshots)
     * @param forceEnabled     false = nunca forzar minimos (solo saltar proveedores insatisfechos)
     */
    public Result optimize(Map<Long, Integer> demand,
                           Map<Long, Double> avgPriceByProduct,
                           Map<Long, Product> productsById,
                           boolean forceEnabled) {
        Result res = new Result();
        double floorPen = pricing.getMinMarginPenPerUnit();

        List<Supplier> suppliers = supplierRepo.findAll().stream()
                .filter(s -> Boolean.TRUE.equals(s.getActive()))
                .toList();
        for (Supplier s : suppliers) res.suppliersById.put(s.getId(), s);

        Map<Long, List<SupplierConstraint>> constraintsBySupplier = constraintRepo.findByActiveTrue().stream()
                .filter(c -> registry.supports(c.getType()))
                .filter(c -> c.getSupplier() != null && res.suppliersById.containsKey(c.getSupplier().getId()))
                .collect(Collectors.groupingBy(c -> c.getSupplier().getId()));

        // ---------- 1. Construir lineas con ofertas, margenes y elegibilidad ----------
        for (Map.Entry<Long, Integer> e : demand.entrySet()) {
            Long pid = e.getKey();
            Product p = productsById.get(pid);
            List<SupplierOffer> offers = offerRepo.findByProduct_IdAndInStockTrueAndSupplier_ActiveTrue(pid).stream()
                    .filter(o -> o.getCostUsd() != null)
                    .toList();
            if (offers.isEmpty() || p == null) {
                res.unfulfillableProductIds.add(pid);
                continue;
            }
            Line line = new Line();
            line.product = p;
            line.qty = e.getValue();
            line.avgUnitPricePen = avgPriceByProduct.get(pid);
            int weightG = p.getWeightG() != null ? p.getWeightG() : 600;
            for (SupplierOffer o : offers) {
                // La mas barata del mismo proveedor manda si hubiera varias.
                line.offers.merge(o.getSupplierId(), o.getCostUsd(), Math::min);
            }
            for (Map.Entry<Long, Double> of : line.offers.entrySet()) {
                Double margin = line.avgUnitPricePen != null
                        ? line.avgUnitPricePen - pricing.landedPen(of.getValue(), weightG)
                        : null;
                line.marginPen.put(of.getKey(), margin);
                if (margin == null || margin >= floorPen) line.eligible.add(of.getKey());
            }
            if (line.eligible.isEmpty()) {
                // Ninguna oferta respeta el piso: el pedido ya existe, hay que comprarlo igual.
                line.eligible.addAll(line.offers.keySet());
                line.marginWarning = true;
            }
            // Baseline: el mas barato elegible.
            Long best = line.eligible.stream()
                    .min(Comparator.comparingDouble(sid -> line.offers.get(sid)))
                    .orElseThrow();
            line.baselineSupplierId = best;
            line.baselineUnitCost = line.offers.get(best);
            res.lines.add(line);
        }
        res.baselineTotalUsd = res.lines.stream().mapToDouble(l -> l.baselineUnitCost * l.qty).sum();

        // ---------- 2. Proveedores con minimos insatisfechos en el baseline ----------
        Map<Integer, Long> baseline = new LinkedHashMap<>();
        for (int i = 0; i < res.lines.size(); i++) baseline.put(i, res.lines.get(i).baselineSupplierId);

        // Restringidos = proveedores CON compra en el baseline que no llegan a su minimo.
        // Un carrito vacio no viola nada: simplemente no se le compra este ciclo.
        List<Long> restricted = new ArrayList<>();
        for (Long sid : constraintsBySupplier.keySet()) {
            if (cartViolated(sid, baseline, res.lines, constraintsBySupplier)) restricted.add(sid);
        }
        // Guardia: si hay demasiados, se enumeran los de mayor participacion en el baseline.
        if (restricted.size() > MAX_ENUM) {
            Map<Long, Double> share = new HashMap<>();
            baseline.forEach((i, sid) -> share.merge(sid,
                    res.lines.get(i).offers.get(sid) * res.lines.get(i).qty, Double::sum));
            restricted.sort(Comparator.comparingDouble(sid -> -share.getOrDefault(sid, 0.0)));
            restricted = restricted.subList(0, MAX_ENUM);
        }

        // ---------- 3. Enumeracion FORZAR/SALTAR (2^k) ----------
        PlanCost best = null;
        Map<Long, double[]> perSupplierBest = new HashMap<>(); // sid -> [bestForce, bestSkip]
        for (Long sid : restricted) perSupplierBest.put(sid, new double[]{Double.NaN, Double.NaN});

        int k = restricted.size();
        for (int mask = 0; mask < (1 << k); mask++) {
            Set<Long> force = new LinkedHashSet<>();
            for (int b = 0; b < k; b++) {
                if ((mask & (1 << b)) != 0) force.add(restricted.get(b));
            }
            if (!forceEnabled && !force.isEmpty()) continue;
            PlanCost pc = evaluatePlan(baseline, res.lines, constraintsBySupplier, restricted, force);
            if (pc == null) continue;
            for (Long sid : restricted) {
                double[] arr = perSupplierBest.get(sid);
                int idx = force.contains(sid) ? 0 : 1;
                if (Double.isNaN(arr[idx]) || pc.totalCost < arr[idx]) arr[idx] = pc.totalCost;
            }
            if (best == null || pc.totalCost < best.totalCost) best = pc;
        }
        if (best == null) {
            // Sin restricciones insatisfechas: el baseline es el plan.
            best = evaluatePlan(baseline, res.lines, constraintsBySupplier, restricted, Set.of());
        }

        // ---------- 4. Mejora 1-opt sobre el plan elegido ----------
        oneOptImprove(best, res.lines, constraintsBySupplier, restricted);

        // ---------- 5. Volcar resultado ----------
        res.assignment = best.assignment;
        res.chosenTotalUsd = assignedCost(best.assignment, res.lines);
        res.penaltiesUsd = best.penaltyCost;
        res.lostSales = best.lostSales;
        res.storeFillNeeds = best.storeFillNeeds;
        for (Long sid : restricted) {
            double[] arr = perSupplierBest.get(sid);
            SupplierDecision d = new SupplierDecision();
            d.supplierId = sid;
            d.supplierName = res.suppliersById.get(sid).getName();
            d.forceTotalUsd = arr[0];
            d.skipTotalUsd = arr[1];
            d.decision = best.forced.contains(sid) ? "FORZAR" : "SALTAR";
            res.skipAnalysis.add(d);
        }
        // Avisos de margen sobre la asignacion FINAL.
        for (Map.Entry<Integer, Long> e : res.assignment.entrySet()) {
            if (e.getValue() == null) continue;
            Line l = res.lines.get(e.getKey());
            Double margin = l.marginPen.get(e.getValue());
            if (margin != null && margin < floorPen) {
                MarginWarning w = new MarginWarning();
                w.productId = l.product.getId();
                w.name = l.product.getBrand() + " " + l.product.getName();
                w.supplierId = e.getValue();
                w.supplierName = res.suppliersById.get(e.getValue()).getName();
                w.marginPen = round(margin);
                w.floorPen = floorPen;
                res.marginWarnings.add(w);
            }
        }
        return res;
    }

    // =====================================================================
    // Evaluacion de un plan (subconjunto F de proveedores forzados)
    // =====================================================================

    private static class PlanCost {
        Map<Integer, Long> assignment;      // index de linea -> supplierId (null = perdida)
        double totalCost;                   // costo lineas + penalidades (para comparar planes)
        double penaltyCost;
        Set<Long> forced;
        List<LostSale> lostSales = new ArrayList<>();
        List<StoreFillNeed> storeFillNeeds = new ArrayList<>();
    }

    private PlanCost evaluatePlan(Map<Integer, Long> baseline, List<Line> lines,
                                  Map<Long, List<SupplierConstraint>> constraintsBySupplier,
                                  List<Long> restricted, Set<Long> force) {
        PlanCost pc = new PlanCost();
        pc.assignment = new LinkedHashMap<>(baseline);
        pc.forced = force;
        double storefillPct = configPct();
        double exchangeRate = pricing.getExchangeRate() > 0 ? pricing.getExchangeRate() : 1;

        Set<Long> skipped = new LinkedHashSet<>(restricted);
        skipped.removeAll(force);

        // 1. Sacar lineas de los proveedores SALTADOS: siguiente oferta elegible que no
        //    este en un proveedor saltado. Si solo quedan ofertas bajo el piso de margen,
        //    se toma la mas barata IGUAL (con aviso de margen): perder la venta completa
        //    siempre cuesta mas que un margen flaco. Sin ninguna oferta -> venta perdida.
        for (Map.Entry<Integer, Long> e : new ArrayList<>(pc.assignment.entrySet())) {
            if (e.getValue() == null || !skipped.contains(e.getValue())) continue;
            Line l = lines.get(e.getKey());
            Long alt = l.eligible.stream()
                    .filter(sid -> !skipped.contains(sid))
                    .min(Comparator.comparingDouble(sid -> l.offers.get(sid)))
                    .orElseGet(() -> l.offers.keySet().stream()
                            .filter(sid -> !skipped.contains(sid))
                            .min(Comparator.comparingDouble(sid -> l.offers.get(sid)))
                            .orElse(null));
            pc.assignment.put(e.getKey(), alt);
            if (alt == null) {
                LostSale ls = new LostSale();
                ls.productId = l.product.getId();
                ls.name = l.product.getBrand() + " " + l.product.getName();
                ls.qty = l.qty;
                ls.reason = "unico proveedor con stock quedo bajo su minimo (saltado)";
                pc.lostSales.add(ls);
                // Perder la venta = devolver el INGRESO ya cobrado + costo intangible
                // (reputacion/reembolso). Sin esto, "ahorrar" $2 saltando un minimo
                // pareceria mejor que conservar una venta de $30.
                double revenuePen = l.avgUnitPricePen != null ? l.avgUnitPricePen * l.qty : 0;
                double intangiblePen = lostSalePenaltyPen() * l.qty;
                pc.penaltyCost += (revenuePen + intangiblePen) / exchangeRate;
            }
        }

        // 2. Forzar los minimos de F: mover lineas por menor penalidad hasta satisfacer.
        List<Long> forceOrdered = force.stream()
                .sorted(Comparator.comparingDouble(sid ->
                        -totalDeficit(sid, pc.assignment, lines, constraintsBySupplier)))
                .toList();
        for (Long sid : forceOrdered) {
            satisfy(sid, pc, lines, constraintsBySupplier, skipped, storefillPct);
        }

        pc.totalCost = assignedCost(pc.assignment, lines) + pc.penaltyCost;
        return pc;
    }

    /** Mueve lineas hacia el proveedor hasta satisfacer TODAS sus restricciones o agotarse. */
    private void satisfy(Long sid, PlanCost pc, List<Line> lines,
                         Map<Long, List<SupplierConstraint>> constraintsBySupplier,
                         Set<Long> skipped, double storefillPct) {
        List<SupplierConstraint> constraints = constraintsBySupplier.getOrDefault(sid, List.of());
        for (SupplierConstraint c : constraints) {
            int guard = 0;
            while (guard++ < 500) {
                double deficit = registry.deficit(cart(sid, pc.assignment, lines), c);
                if (deficit <= 0) break;
                Integer bestIdx = null;
                double bestPenalty = Double.MAX_VALUE;
                for (int i = 0; i < lines.size(); i++) {
                    Long current = pc.assignment.get(i);
                    if (current == null || current.equals(sid)) continue;
                    Line l = lines.get(i);
                    if (!l.eligible.contains(sid)) continue;
                    if (!movesHelp(c, l)) continue;
                    // Mover no debe dejar al proveedor de ORIGEN comprando bajo su minimo
                    // (vaciarlo por completo si es valido: carrito vacio = no comprarle).
                    pc.assignment.put(i, sid);
                    boolean breaksSource = cartViolated(current, pc.assignment, lines, constraintsBySupplier);
                    pc.assignment.put(i, current);
                    if (breaksSource) continue;
                    double penalty = (l.offers.get(sid) - l.offers.get(current)) * l.qty;
                    if (penalty < bestPenalty) {
                        bestPenalty = penalty;
                        bestIdx = i;
                    }
                }
                if (bestIdx == null) {
                    // No hay mas lineas movibles: el resto se cubre con relleno de tienda.
                    StoreFillNeed need = new StoreFillNeed();
                    need.supplierId = sid;
                    need.constraintType = c.getType();
                    need.remainingDeficit = round(deficit);
                    pc.storeFillNeeds.add(need);
                    double deficitUsd = "MIN_ORDER_USD".equals(c.getType())
                            ? deficit
                            : deficit * estUnitCost(sid, lines);
                    pc.penaltyCost += deficitUsd * storefillPct;
                    break;
                }
                pc.assignment.put(bestIdx, sid);
            }
        }
    }

    /** ¿Mover esta linea reduce el deficit de ESTA restriccion? (marca para PER_BRAND). */
    private boolean movesHelp(SupplierConstraint c, Line l) {
        if (!"MIN_UNITS_PER_BRAND".equals(c.getType())) return true;
        String brand = new MinUnitsPerBrandEvaluator().scopeBrand(c);
        return brand != null && l.product.getBrand() != null
                && brand.equalsIgnoreCase(l.product.getBrand());
    }

    /** Pasada 1-opt: devolver lineas movidas a opciones mas baratas si nada se rompe. */
    private void oneOptImprove(PlanCost pc, List<Line> lines,
                               Map<Long, List<SupplierConstraint>> constraintsBySupplier,
                               List<Long> restricted) {
        Set<Long> skipped = new LinkedHashSet<>(restricted);
        skipped.removeAll(pc.forced);
        boolean improved = true;
        int iter = 0;
        while (improved && iter++ < ONE_OPT_ITERATIONS) {
            improved = false;
            for (int i = 0; i < lines.size(); i++) {
                Long current = pc.assignment.get(i);
                if (current == null) continue;
                Line l = lines.get(i);
                double currentCost = l.offers.get(current) * l.qty;
                for (Long alt : l.eligible) {
                    if (alt.equals(current) || skipped.contains(alt)) continue;
                    double altCost = l.offers.get(alt) * l.qty;
                    if (altCost >= currentCost) continue;
                    // Probar el movimiento: los forzados siguen satisfechos y ni el origen
                    // ni el destino quedan comprando bajo su minimo.
                    pc.assignment.put(i, alt);
                    boolean ok = !cartViolated(current, pc.assignment, lines, constraintsBySupplier)
                            && !cartViolated(alt, pc.assignment, lines, constraintsBySupplier);
                    if (ok) {
                        for (Long f : pc.forced) {
                            // Un proveedor con relleno pendiente ya esta "cubierto": no re-validar.
                            boolean hasFill = pc.storeFillNeeds.stream().anyMatch(n -> n.supplierId.equals(f));
                            if (!hasFill && anyDeficit(f, pc.assignment, lines, constraintsBySupplier)) {
                                ok = false;
                                break;
                            }
                        }
                    }
                    if (ok) {
                        improved = true;
                        currentCost = altCost;
                        current = alt;
                    } else {
                        pc.assignment.put(i, current);
                    }
                }
            }
        }
        pc.totalCost = assignedCost(pc.assignment, lines) + pc.penaltyCost;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private SupplierCart cart(Long sid, Map<Integer, Long> assignment, List<Line> lines) {
        SupplierCart cart = new SupplierCart();
        for (Map.Entry<Integer, Long> e : assignment.entrySet()) {
            if (sid.equals(e.getValue())) {
                Line l = lines.get(e.getKey());
                cart.add(l.offers.get(sid), l.qty, l.product.getBrand());
            }
        }
        return cart;
    }

    private boolean anyDeficit(Long sid, Map<Integer, Long> assignment, List<Line> lines,
                               Map<Long, List<SupplierConstraint>> constraintsBySupplier) {
        SupplierCart cart = cart(sid, assignment, lines);
        for (SupplierConstraint c : constraintsBySupplier.getOrDefault(sid, List.of())) {
            if (registry.deficit(cart, c) > 0) return true;
        }
        return false;
    }

    /**
     * ¿El carrito viola alguna restriccion? Un carrito VACIO nunca viola nada:
     * no comprarle a un proveedor es siempre valido; el minimo aplica solo si se le compra.
     */
    private boolean cartViolated(Long sid, Map<Integer, Long> assignment, List<Line> lines,
                                 Map<Long, List<SupplierConstraint>> constraintsBySupplier) {
        if (sid == null) return false;
        SupplierCart cart = cart(sid, assignment, lines);
        if (cart.units == 0) return false;
        for (SupplierConstraint c : constraintsBySupplier.getOrDefault(sid, List.of())) {
            if (registry.deficit(cart, c) > 0) return true;
        }
        return false;
    }

    private double totalDeficit(Long sid, Map<Integer, Long> assignment, List<Line> lines,
                                Map<Long, List<SupplierConstraint>> constraintsBySupplier) {
        SupplierCart cart = cart(sid, assignment, lines);
        double sum = 0;
        for (SupplierConstraint c : constraintsBySupplier.getOrDefault(sid, List.of())) {
            sum += Math.max(0, registry.deficit(cart, c));
        }
        return sum;
    }

    private double assignedCost(Map<Integer, Long> assignment, List<Line> lines) {
        double sum = 0;
        for (Map.Entry<Integer, Long> e : assignment.entrySet()) {
            if (e.getValue() == null) continue;
            Line l = lines.get(e.getKey());
            sum += l.offers.get(e.getValue()) * l.qty;
        }
        return sum;
    }

    /** Costo unitario estimado del catalogo del proveedor (para valorar relleno en unidades). */
    private double estUnitCost(Long sid, List<Line> lines) {
        return lines.stream()
                .filter(l -> l.offers.containsKey(sid))
                .mapToDouble(l -> l.offers.get(sid))
                .min()
                .orElse(10.0);
    }

    private double configPct() {
        return pricing.getStorefillPenaltyPct() / 100.0;
    }

    private double lostSalePenaltyPen() {
        return pricing.getLostSalePenaltyPen();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
