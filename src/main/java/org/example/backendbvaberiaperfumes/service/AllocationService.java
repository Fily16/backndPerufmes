package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.allocation.AllocationOptimizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fachada del motor de asignacion de compra. Mantiene el contrato del endpoint
 * historico (GET /allocation) y agrega el flujo con plan persistido:
 * compute (DRAFT) -> confirm (CONFIRMED, con guardia de margen) -> la ganancia
 * del consolidado usa el costo REAL del plan confirmado.
 *
 * La logica de optimizacion vive en service/allocation/AllocationOptimizer;
 * las restricciones de proveedor son datos (SupplierConstraint), ya no hay
 * un "shape Zimaxx" cableado en el codigo.
 */
@Service
public class AllocationService {

    private final ConsolidadoService consolidadoService;
    private final SupplierRepository supplierRepo;
    private final SupplierOfferRepository offerRepo;
    private final ProductRepository productRepo;
    private final AppConfigRepository configRepo;
    private final SupplierConstraintRepository constraintRepo;
    private final PurchasePlanRepository planRepo;
    private final AllocationOptimizer optimizer;
    private final PricingService pricing;

    public AllocationService(ConsolidadoService consolidadoService, SupplierRepository supplierRepo,
                             SupplierOfferRepository offerRepo, ProductRepository productRepo,
                             AppConfigRepository configRepo, SupplierConstraintRepository constraintRepo,
                             PurchasePlanRepository planRepo, AllocationOptimizer optimizer,
                             PricingService pricing) {
        this.consolidadoService = consolidadoService;
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.productRepo = productRepo;
        this.configRepo = configRepo;
        this.constraintRepo = constraintRepo;
        this.planRepo = planRepo;
        this.optimizer = optimizer;
        this.pricing = pricing;
    }

    /** Calculo advisory (endpoint historico): no persiste nada. */
    public AllocationResponse computeAllocation(Long consolidadoId) {
        return buildResponse(consolidadoId, runOptimizer(consolidadoId));
    }

    /** Calcula Y persiste un plan DRAFT (reemplaza drafts anteriores del consolidado). */
    @Transactional
    public AllocationResponse computeAndSaveDraft(Long consolidadoId) {
        AllocationOptimizer.Result r = runOptimizer(consolidadoId);
        AllocationResponse resp = buildResponse(consolidadoId, r);

        for (PurchasePlan old : planRepo.findByConsolidadoIdAndStatus(consolidadoId, "DRAFT")) {
            old.setStatus("SUPERSEDED");
            planRepo.save(old);
        }
        PurchasePlan plan = new PurchasePlan();
        plan.setConsolidadoId(consolidadoId);
        plan.setBaselineTotalUsd(round(r.baselineTotalUsd));
        plan.setTotalUsd(round(r.chosenTotalUsd));
        plan.setExtraCostUsd(round(r.chosenTotalUsd - r.baselineTotalUsd));
        for (Map.Entry<Integer, Long> e : r.assignment.entrySet()) {
            AllocationOptimizer.Line l = r.lines.get(e.getKey());
            PurchasePlanLine line = new PurchasePlanLine();
            line.setPlan(plan);
            line.setProductId(l.product.getId());
            line.setSupplierId(e.getValue());
            line.setQty(l.qty);
            if (e.getValue() != null) {
                line.setUnitCostUsd(l.offers.get(e.getValue()));
                boolean moved = !e.getValue().equals(l.baselineSupplierId);
                line.setMovedToReachMin(moved);
                line.setPenaltyUsd(moved ? round(l.offers.get(e.getValue()) - l.baselineUnitCost) : 0.0);
                if (moved) line.setReason("movido para cumplir minimo del proveedor");
            } else {
                line.setReason("sin proveedor viable (venta perdida)");
            }
            plan.getLines().add(line);
        }
        plan = planRepo.save(plan);
        resp.planId = plan.getId();
        return resp;
    }

    /**
     * Confirma el plan. Si alguna linea queda bajo el piso de margen y force=false,
     * lanza MarginFloorException (el controller responde 409 con el detalle).
     */
    @Transactional
    public PurchasePlan confirmPlan(Long consolidadoId, Long planId, boolean force) {
        PurchasePlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan no existe: " + planId));
        if (!consolidadoId.equals(plan.getConsolidadoId())) {
            throw new IllegalArgumentException("El plan no pertenece a este consolidado");
        }
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new IllegalStateException("Solo se puede confirmar un plan DRAFT (actual: " + plan.getStatus() + ")");
        }

        List<AllocationResponse.MarginWarning> warnings = marginWarningsOf(plan);
        if (!warnings.isEmpty() && !force) {
            throw new MarginFloorException(warnings);
        }

        for (PurchasePlan old : planRepo.findByConsolidadoIdAndStatus(consolidadoId, "CONFIRMED")) {
            old.setStatus("SUPERSEDED");
            planRepo.save(old);
        }
        plan.setStatus("CONFIRMED");
        plan.setConfirmedAt(LocalDateTime.now());
        plan = planRepo.save(plan);
        // La ganancia del consolidado pasa a calcularse contra el costo real del plan.
        consolidadoService.recalculateConsolidado(consolidadoId);
        return plan;
    }

    public Optional<PurchasePlan> currentPlan(Long consolidadoId) {
        Optional<PurchasePlan> confirmed =
                planRepo.findFirstByConsolidadoIdAndStatusOrderByCreatedAtDesc(consolidadoId, "CONFIRMED");
        if (confirmed.isPresent()) return confirmed;
        return planRepo.findFirstByConsolidadoIdAndStatusOrderByCreatedAtDesc(consolidadoId, "DRAFT");
    }

    /** Margen por linea contra el precio ya cobrado: el reporte que evita vender a perdida. */
    public List<Map<String, Object>> marginReport(Long consolidadoId) {
        Map<Long, Double> avgPrice = consolidadoService.getActiveAvgPriceByProduct(consolidadoId);
        Map<Long, Integer> demand = consolidadoService.getActiveDemandByProduct(consolidadoId);
        Map<Long, Double> planCost = confirmedPlanCosts(consolidadoId);
        double floor = pricing.getMinMarginPenPerUnit();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : demand.entrySet()) {
            Product p = productRepo.findById(e.getKey()).orElse(null);
            if (p == null) continue;
            int weightG = p.getWeightG() != null ? p.getWeightG() : 600;
            Double price = avgPrice.get(e.getKey());
            Double costUsd = planCost.get(e.getKey());
            if (costUsd == null) costUsd = p.getPriceUsd();
            Double landed = costUsd != null ? pricing.landedPen(costUsd, weightG) : null;
            Double margin = (price != null && landed != null) ? price - landed : null;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", p.getId());
            m.put("name", p.getBrand() + " " + p.getName());
            m.put("quantity", e.getValue());
            m.put("avgUnitPricePen", price != null ? round(price) : null);
            m.put("unitCostUsd", costUsd != null ? round(costUsd) : null);
            m.put("landedPen", landed != null ? round(landed) : null);
            m.put("marginPen", margin != null ? round(margin) : null);
            m.put("belowFloor", margin != null && margin < floor);
            m.put("costSource", planCost.containsKey(e.getKey()) ? "PLAN_CONFIRMADO" : "BASE_ACTUAL");
            out.add(m);
        }
        return out;
    }

    /** Costos por producto del plan CONFIRMADO vigente (vacio si no hay). */
    public Map<Long, Double> confirmedPlanCosts(Long consolidadoId) {
        return planRepo.findFirstByConsolidadoIdAndStatusOrderByCreatedAtDesc(consolidadoId, "CONFIRMED")
                .map(plan -> {
                    Map<Long, Double> m = new HashMap<>();
                    for (PurchasePlanLine l : plan.getLines()) {
                        if (l.getUnitCostUsd() != null) m.put(l.getProductId(), l.getUnitCostUsd());
                    }
                    return m;
                })
                .orElse(Map.of());
    }

    public static class MarginFloorException extends RuntimeException {
        public final transient List<AllocationResponse.MarginWarning> warnings;
        public MarginFloorException(List<AllocationResponse.MarginWarning> warnings) {
            super("Hay lineas bajo el piso de margen; confirma con force=true para aceptarlas");
            this.warnings = warnings;
        }
    }

    // =====================================================================

    private AllocationOptimizer.Result runOptimizer(Long consolidadoId) {
        Map<Long, Integer> demand = consolidadoService.getActiveDemandByProduct(consolidadoId);
        Map<Long, Double> avgPrice = consolidadoService.getActiveAvgPriceByProduct(consolidadoId);
        Map<Long, Product> products = new HashMap<>();
        for (Long pid : demand.keySet()) {
            productRepo.findById(pid).ifPresent(p -> products.put(pid, p));
        }
        boolean forceEnabled = "true".equalsIgnoreCase(
                configValue("allocation_priority_enabled",
                        configValue("zimaxx_priority_enabled", "true")));
        return optimizer.optimize(demand, avgPrice, products, forceEnabled);
    }

    private AllocationResponse buildResponse(Long consolidadoId, AllocationOptimizer.Result r) {
        AllocationResponse resp = new AllocationResponse();
        resp.consolidadoId = consolidadoId;
        resp.baselineTotalUsd = round(r.baselineTotalUsd);
        resp.chosenTotalUsd = round(r.chosenTotalUsd);
        resp.extraCostUsd = round(r.chosenTotalUsd - r.baselineTotalUsd);
        resp.penaltiesUsd = round(r.penaltiesUsd);

        // Grupos por proveedor.
        Map<Long, AllocationResponse.SupplierAllocation> groups = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : r.assignment.entrySet()) {
            if (e.getValue() == null) continue;
            AllocationOptimizer.Line l = r.lines.get(e.getKey());
            Supplier s = r.suppliersById.get(e.getValue());
            AllocationResponse.SupplierAllocation g = groups.computeIfAbsent(e.getValue(), sid -> {
                AllocationResponse.SupplierAllocation ga = new AllocationResponse.SupplierAllocation();
                ga.supplierId = sid;
                ga.name = s != null ? s.getName() : ("#" + sid);
                ga.minOrderUsd = s != null && s.getMinOrderUsd() != null ? s.getMinOrderUsd() : 0;
                return ga;
            });
            double unitCost = l.offers.get(e.getValue());
            AllocationResponse.AllocationLine line = new AllocationResponse.AllocationLine();
            line.productId = l.product.getId();
            line.brand = l.product.getBrand();
            line.name = l.product.getName();
            line.ml = l.product.getMl();
            line.quantity = l.qty;
            line.unitCostUsd = round(unitCost);
            line.subtotalUsd = round(unitCost * l.qty);
            line.movedToReachMin = !e.getValue().equals(l.baselineSupplierId);
            line.penaltyUsd = line.movedToReachMin ? round(unitCost - l.baselineUnitCost) : 0;
            g.lines.add(line);
            g.subtotalUsd += unitCost * l.qty;
        }
        for (AllocationResponse.SupplierAllocation g : groups.values()) {
            g.subtotalUsd = round(g.subtotalUsd);
            g.reachedMin = g.subtotalUsd >= g.minOrderUsd;
            g.lines.sort(Comparator.comparingDouble((AllocationResponse.AllocationLine l) -> l.subtotalUsd).reversed());
            resp.suppliers.add(g);
        }

        // Sin proveedor con stock.
        for (Long pid : r.unfulfillableProductIds) {
            Product p = productRepo.findById(pid).orElse(null);
            AllocationResponse.UnfulfillableItem u = new AllocationResponse.UnfulfillableItem();
            u.productId = pid;
            u.brand = p != null ? p.getBrand() : null;
            u.name = p != null ? p.getName() : ("#" + pid);
            u.quantity = 0;
            resp.unfulfillable.add(u);
        }

        // Analisis forzar/saltar + avisos de margen + ventas perdidas.
        for (AllocationOptimizer.SupplierDecision d : r.skipAnalysis) {
            AllocationResponse.SupplierDecision sd = new AllocationResponse.SupplierDecision();
            sd.supplierId = d.supplierId;
            sd.name = d.supplierName;
            sd.forceTotalUsd = Double.isNaN(d.forceTotalUsd) ? null : round(d.forceTotalUsd);
            sd.skipTotalUsd = Double.isNaN(d.skipTotalUsd) ? null : round(d.skipTotalUsd);
            sd.decision = d.decision;
            resp.skipAnalysis.add(sd);
            resp.notes.add(d.supplierName + ": forzar su minimo cuesta $"
                    + (sd.forceTotalUsd != null ? fmt(sd.forceTotalUsd) : "-")
                    + " vs saltarlo $" + (sd.skipTotalUsd != null ? fmt(sd.skipTotalUsd) : "-")
                    + " -> decision: " + d.decision);
        }
        for (AllocationOptimizer.MarginWarning w : r.marginWarnings) {
            AllocationResponse.MarginWarning mw = new AllocationResponse.MarginWarning();
            mw.productId = w.productId;
            mw.name = w.name;
            mw.supplierId = w.supplierId;
            mw.supplierName = w.supplierName;
            mw.marginPen = w.marginPen;
            mw.floorPen = w.floorPen;
            resp.marginWarnings.add(mw);
        }
        for (AllocationOptimizer.LostSale ls : r.lostSales) {
            AllocationResponse.LostSale rls = new AllocationResponse.LostSale();
            rls.productId = ls.productId;
            rls.name = ls.name;
            rls.quantity = ls.qty;
            rls.reason = ls.reason;
            resp.lostSales.add(rls);
        }

        // Compatibilidad con la UI actual (campos "Zimaxx"): proveedor prioritario.
        Supplier priority = r.suppliersById.values().stream()
                .filter(s -> Boolean.TRUE.equals(s.getPriorityToReachMin()))
                .findFirst().orElse(null);
        boolean forceEnabled = "true".equalsIgnoreCase(
                configValue("allocation_priority_enabled", configValue("zimaxx_priority_enabled", "true")));
        resp.zimaxxPriorityEnabled = forceEnabled && priority != null;
        if (priority != null) {
            double remaining = r.storeFillNeeds.stream()
                    .filter(n -> priority.getId().equals(n.supplierId))
                    .mapToDouble(n -> "MIN_ORDER_USD".equals(n.constraintType) ? n.remainingDeficit : 0)
                    .sum();
            boolean skippedByPlan = r.skipAnalysis.stream()
                    .anyMatch(d -> priority.getId().equals(d.supplierId) && "SALTAR".equals(d.decision));
            resp.zimaxxMinReached = !skippedByPlan && remaining <= 0;
            resp.zimaxxGapUsd = round(remaining);
            if (remaining > 0) {
                buildStoreFill(resp, priority, r);
            }
            if (skippedByPlan) {
                resp.notes.add("El plan mas barato SALTA a " + priority.getName()
                        + " este ciclo. Puedes forzarlo subiendo lost_sale_penalty_pen o confirmando a mano.");
            }
        }
        if (resp.extraCostUsd > 0) {
            resp.notes.add("Cumplir los minimos cuesta $" + fmt(resp.extraCostUsd)
                    + " extra vs comprar todo al mas barato.");
        }
        if (!resp.marginWarnings.isEmpty()) {
            resp.notes.add(resp.marginWarnings.size()
                    + " linea(s) quedarian bajo el piso de margen (S/"
                    + fmt(pricing.getMinMarginPenPerUnit()) + "/unidad).");
        }
        if (!resp.unfulfillable.isEmpty()) {
            resp.notes.add(resp.unfulfillable.size() + " producto(s) sin stock en ningun proveedor.");
        }
        if (r.lines.isEmpty() && resp.unfulfillable.isEmpty()) {
            resp.notes.add("No hay demanda de clientes activa en este consolidado.");
        }
        return resp;
    }

    private void buildStoreFill(AllocationResponse resp, Supplier supplier, AllocationOptimizer.Result r) {
        Set<Long> demanded = r.lines.stream().map(l -> l.product.getId()).collect(Collectors.toSet());
        List<SupplierOffer> offers = offerRepo.findBySupplier_Id(supplier.getId()).stream()
                .filter(o -> Boolean.TRUE.equals(o.getInStock()) && o.getCostUsd() != null)
                .filter(o -> !demanded.contains(o.getProduct().getId()))
                .sorted(Comparator.comparingDouble(SupplierOffer::getCostUsd).reversed())
                .limit(15)
                .toList();
        for (SupplierOffer o : offers) {
            AllocationResponse.FillSuggestion f = new AllocationResponse.FillSuggestion();
            f.productId = o.getProduct().getId();
            f.brand = o.getProduct().getBrand();
            f.name = o.getProduct().getName();
            f.costUsd = round(o.getCostUsd());
            resp.storeFillSuggestions.add(f);
        }
    }

    private List<AllocationResponse.MarginWarning> marginWarningsOf(PurchasePlan plan) {
        Map<Long, Double> avgPrice = consolidadoService.getActiveAvgPriceByProduct(plan.getConsolidadoId());
        double floor = pricing.getMinMarginPenPerUnit();
        List<AllocationResponse.MarginWarning> out = new ArrayList<>();
        for (PurchasePlanLine l : plan.getLines()) {
            if (l.getUnitCostUsd() == null) continue;
            Double price = avgPrice.get(l.getProductId());
            if (price == null) continue;
            Product p = productRepo.findById(l.getProductId()).orElse(null);
            int weightG = p != null && p.getWeightG() != null ? p.getWeightG() : 600;
            double margin = price - pricing.landedPen(l.getUnitCostUsd(), weightG);
            if (margin < floor) {
                AllocationResponse.MarginWarning w = new AllocationResponse.MarginWarning();
                w.productId = l.getProductId();
                w.name = p != null ? p.getBrand() + " " + p.getName() : ("#" + l.getProductId());
                w.supplierId = l.getSupplierId();
                w.marginPen = round(margin);
                w.floorPen = floor;
                out.add(w);
            }
        }
        return out;
    }

    private String configValue(String key, String def) {
        return configRepo.findByConfigKey(key).map(AppConfig::getConfigValue).orElse(def);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
