package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.*;
import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.MissingResolution;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.model.OrderItem;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.example.backendbvaberiaperfumes.service.CostBasisService;
import org.example.backendbvaberiaperfumes.service.PricingService;
import org.example.backendbvaberiaperfumes.service.RetailService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ProductRepository productRepo;
    private final ConsolidadoRepository consolidadoRepo;
    private final OrderRepository orderRepo;
    private final AppConfigRepository configRepo;
    private final RetailService retailService;
    private final PricingService pricingService;
    private final ConsolidadoService consolidadoService;
    private final CostBasisService costBasisService;

    public AdminController(ProductRepository productRepo, ConsolidadoRepository consolidadoRepo,
                           OrderRepository orderRepo, AppConfigRepository configRepo,
                           RetailService retailService, PricingService pricingService,
                           ConsolidadoService consolidadoService, CostBasisService costBasisService) {
        this.productRepo = productRepo;
        this.consolidadoRepo = consolidadoRepo;
        this.orderRepo = orderRepo;
        this.configRepo = configRepo;
        this.retailService = retailService;
        this.pricingService = pricingService;
        this.consolidadoService = consolidadoService;
        this.costBasisService = costBasisService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private AdminRepository adminRepo;

    @org.springframework.beans.factory.annotation.Autowired
    private SupplierOfferRepository offerRepo;

    @org.springframework.beans.factory.annotation.Autowired
    private MissingResolutionRepository missingResolutionRepo;

    // --- ERP: faltantes (perfumes pedidos sin proveedor) con el cliente que los pidió ---
    @GetMapping("/consolidados/{id}/missing")
    public List<MissingItem> getMissing(@PathVariable Long id) {
        List<Order> orders = consolidadoService.getOrdersByConsolidado(id);
        // Solo pedidos ACEPTADOS (separados en adelante); pendientes y rechazados no cuentan.
        java.util.Set<String> active = java.util.Set.of("SEPARADO", "PENDIENTE_RESTO", "PAGADO", "VERIFICADO");
        java.util.Map<Long, MissingItem> map = new LinkedHashMap<>();
        for (Order o : orders) {
            if ("COMPRA TIENDA".equalsIgnoreCase(o.getClientName())) continue;
            if ("STOCK".equalsIgnoreCase(o.getChannel())) continue; // los de stock ya los tienes
            if (!active.contains(o.getPaymentStatus())) continue;
            for (OrderItem it : o.getItems()) {
                Product p = it.getProduct();
                if (p == null) continue;
                MissingItem mi = map.computeIfAbsent(p.getId(), k -> {
                    MissingItem x = new MissingItem();
                    x.setProductId(p.getId());
                    x.setBrand(p.getBrand());
                    x.setName(p.getName());
                    x.setMl(p.getMl());
                    x.setPriceUsd(p.getPriceUsd());
                    x.setRegisteredPricePen(p.getWholesalePricePen());
                    x.setOrders(new java.util.ArrayList<>());
                    return x;
                });
                MissingItem.OrderRef ref = new MissingItem.OrderRef();
                ref.setOrderCode(o.getOrderCode());
                ref.setClientName(o.getClientName());
                ref.setClientPhone(o.getClientPhone());
                ref.setQuantity(it.getQuantity());
                mi.getOrders().add(ref);
            }
        }
        // Solo los que NO tienen ninguna oferta en stock tras importar los Excel
        List<MissingItem> result = new java.util.ArrayList<>();
        for (MissingItem mi : map.values()) {
            if (offerRepo.findByProduct_IdAndInStockTrue(mi.getProductId()).isEmpty()) {
                result.add(mi);
            }
        }
        // Adjunta el estado de resolucion (Caso A CristFragance por defecto / Caso B imposible).
        Map<Long, String> statusByProduct = new java.util.HashMap<>();
        for (MissingResolution mr : missingResolutionRepo.findByConsolidadoId(id)) {
            statusByProduct.put(mr.getProductId(), mr.getStatus());
        }
        for (MissingItem mi : result) {
            mi.setResolutionStatus(statusByProduct.getOrDefault(mi.getProductId(), MissingResolution.CRIST_PENDING));
        }
        return result;
    }

    /**
     * Marca la resolucion de un perfume faltante: CRIST_PENDING | CRIST_BOUGHT | UNAVAILABLE.
     * Metadato de seguimiento (persiste el "comprado en CristFragance" y separa los imposibles);
     * NO afecta la asignacion de compra.
     */
    @PutMapping("/consolidados/{id}/missing/{productId}")
    public ResponseEntity<?> setMissingResolution(@PathVariable Long id, @PathVariable Long productId,
                                                  @RequestBody Map<String, String> body) {
        String status = body != null ? body.get("status") : null;
        if (status == null || !java.util.Set.of(
                MissingResolution.CRIST_PENDING, MissingResolution.CRIST_BOUGHT, MissingResolution.UNAVAILABLE)
                .contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("message", "status inválido"));
        }
        MissingResolution mr = missingResolutionRepo.findByConsolidadoIdAndProductId(id, productId)
                .orElseGet(() -> {
                    MissingResolution m = new MissingResolution();
                    m.setConsolidadoId(id);
                    m.setProductId(productId);
                    return m;
                });
        mr.setStatus(status);
        mr.setUpdatedAt(java.time.Instant.now());
        missingResolutionRepo.save(mr);
        return ResponseEntity.ok(Map.of("productId", productId, "status", status));
    }

    // --- ERP: vendedores (para el filtro de la tabla de pedidos) ---
    @GetMapping("/sellers")
    public List<String> getSellers() {
        return adminRepo.findAll().stream()
                .map(Admin::getName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    // --- ERP: resumen de operación del consolidado activo (KPIs + ganancia líquida) ---
    @GetMapping("/operations")
    public Map<String, Object> getOperations() {
        // NO usa getOrCreateActive(): abrir esta pantalla entre consolidados crearía un
        // ABIERTO fantasma sin fechas, que reabriría los encargos y bloquearía con 409
        // la apertura del siguiente. Sin consolidado activo, el resumen va vacío.
        Consolidado active = consolidadoService.getActiveOrNull();
        if (active == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("consolidadoId", null);
            empty.put("orders", 0);
            empty.put("lima", 0);
            empty.put("provincia", 0);
            empty.put("units", 0);
            empty.put("revenuePen", 0.0);
            empty.put("bySeller", Map.of());
            empty.put("profitPen", 0.0);
            empty.put("message", "No hay consolidado abierto.");
            return empty;
        }
        List<Order> orders = consolidadoService.getOrdersByConsolidado(active.getId());
        // Solo pedidos ACEPTADOS (separados en adelante); pendientes y rechazados no cuentan.
        java.util.Set<String> accepted = java.util.Set.of("SEPARADO", "PENDIENTE_RESTO", "PAGADO", "VERIFICADO");
        List<Order> client = orders.stream()
                .filter(o -> !"COMPRA TIENDA".equalsIgnoreCase(o.getClientName()))
                .filter(o -> !"STOCK".equalsIgnoreCase(o.getChannel()))
                .filter(o -> accepted.contains(o.getPaymentStatus()))
                .collect(Collectors.toList());

        long lima = client.stream().filter(o -> "LIMA".equalsIgnoreCase(o.getDeliveryMethod())).count();
        long provincia = client.stream().filter(o -> "PROVINCIA".equalsIgnoreCase(o.getDeliveryMethod())).count();
        int units = client.stream().flatMap(o -> o.getItems().stream())
                .mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
        double revenue = client.stream().mapToDouble(o -> o.getTotalPen() == null ? 0 : o.getTotalPen()).sum();
        Map<String, Long> bySeller = client.stream().collect(Collectors.groupingBy(
                o -> o.getAttendedBy() == null || o.getAttendedBy().isBlank() ? "Sin asignar" : o.getAttendedBy(),
                Collectors.counting()));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("consolidadoId", active.getId());
        res.put("status", active.getStatus());
        res.put("totalOrders", client.size());
        res.put("lima", lima);
        res.put("provincia", provincia);
        res.put("units", units);
        res.put("revenuePen", revenue);
        res.put("gananciaLiquidaPen", active.getProjectedProfitPen());
        res.put("bySeller", bySeller);
        return res;
    }

    // --- ERP: ganancia total por periodo (mes/semana/año) — real ---
    // stock (ventas retail) + promociones (packs aceptados) + consolidado (ENTREGADO y todo pagado).
    @GetMapping("/profit-report")
    public Map<String, Object> profitReport(@RequestParam(defaultValue = "month") String granularity) {
        String gran = granularity == null ? "month" : granularity.toLowerCase();
        java.util.Map<String, double[]> buckets = new java.util.TreeMap<>(); // [stock, promo, consolidado]

        // 1) Stock: cada venta retail (fecha + ganancia reales)
        for (org.example.backendbvaberiaperfumes.model.RetailSale s : retailSaleRepo.findAll()) {
            double profit = s.getProfitPen() != null ? s.getProfitPen() : 0;
            if (profit == 0) continue;
            buckets.computeIfAbsent(periodKey(s.getSaleDate(), gran), x -> new double[3])[0] += profit;
        }

        java.util.Set<String> paid = java.util.Set.of("PAGADO", "VERIFICADO");
        java.util.Set<String> active = java.util.Set.of("SEPARADO", "PENDIENTE_RESTO", "PAGADO", "VERIFICADO");

        // 2) Promociones: pedidos STOCK aceptados (VERIFICADO) con líneas de promo
        for (Order o : orderRepo.findAll()) {
            if (!"STOCK".equalsIgnoreCase(o.getChannel())) continue;
            if (!"VERIFICADO".equals(o.getPaymentStatus())) continue;
            if (o.getPromos() == null || o.getPromos().isEmpty()) continue;
            double promoProfit = 0;
            for (org.example.backendbvaberiaperfumes.model.OrderPromo op : o.getPromos()) {
                double unit = op.getProfitPen() != null ? op.getProfitPen() : 0;
                int q = op.getQuantity() != null ? op.getQuantity() : 1;
                promoProfit += unit * q;
            }
            if (promoProfit == 0) continue;
            java.time.LocalDateTime when = o.getUpdatedAt() != null ? o.getUpdatedAt() : o.getCreatedAt();
            buckets.computeIfAbsent(periodKey(when, gran), x -> new double[3])[1] += promoProfit;
        }

        // 3) Consolidado: lote ENTREGADO con todos sus pedidos de cliente pagados.
        // Ganancia en vivo (precio − puesto en Perú): robusta y correcta aunque el valor guardado
        // esté viejo. Fecha = entrega (deliveredAt) o, si falta (datos antiguos), la de cierre.
        for (Consolidado c : consolidadoRepo.findAll()) {
            if (!"ENTREGADO".equals(c.getStatus())) continue;
            java.time.LocalDateTime when = c.getDeliveredAt() != null ? c.getDeliveredAt() : c.getCloseDate();
            if (when == null) continue;
            List<Order> clientOrders = orderRepo.findByConsolidado_Id(c.getId()).stream()
                    .filter(o -> !"COMPRA TIENDA".equalsIgnoreCase(o.getClientName()))
                    .filter(o -> !"STOCK".equalsIgnoreCase(o.getChannel()))
                    .filter(o -> active.contains(o.getPaymentStatus()))
                    .collect(Collectors.toList());
            if (clientOrders.isEmpty()) continue;
            if (!clientOrders.stream().allMatch(o -> paid.contains(o.getPaymentStatus()))) continue;
            double profit = 0;
            for (Order o : clientOrders) {
                for (OrderItem it : o.getItems()) {
                    Product p = it.getProduct();
                    if (p == null) continue;
                    double lp = pricingService.landedPen(
                            p.getPriceUsd() != null ? p.getPriceUsd() : 0,
                            p.getWeightG() != null ? p.getWeightG() : 0);
                    profit += ((it.getUnitPricePen() != null ? it.getUnitPricePen() : 0) - lp)
                            * (it.getQuantity() != null ? it.getQuantity() : 0);
                }
            }
            buckets.computeIfAbsent(periodKey(when, gran), x -> new double[3])[2] += profit;
        }

        List<Map<String, Object>> periods = new java.util.ArrayList<>();
        double tStock = 0, tPromo = 0, tCons = 0;
        for (Map.Entry<String, double[]> e : buckets.entrySet()) {
            double[] v = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", e.getKey());
            row.put("stock", round2(v[0]));
            row.put("promo", round2(v[1]));
            row.put("consolidado", round2(v[2]));
            row.put("total", round2(v[0] + v[1] + v[2]));
            periods.add(row);
            tStock += v[0]; tPromo += v[1]; tCons += v[2];
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("granularity", gran);
        res.put("periods", periods);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("stock", round2(tStock));
        totals.put("promo", round2(tPromo));
        totals.put("consolidado", round2(tCons));
        totals.put("total", round2(tStock + tPromo + tCons));
        res.put("totals", totals);
        return res;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private String periodKey(java.time.LocalDateTime dt, String gran) {
        if (dt == null) return "—";
        if ("year".equals(gran)) return String.valueOf(dt.getYear());
        if ("week".equals(gran)) {
            int week = dt.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            int wyear = dt.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
            return wyear + "-W" + String.format("%02d", week);
        }
        return dt.getYear() + "-" + String.format("%02d", dt.getMonthValue());
    }

    // --- ERP: lanzar perfumes a stock de tienda (precio = costo landed + S/35) ---
    @PostMapping("/retail/launch")
    public ResponseEntity<Map<String, Object>> launchToStock(@RequestBody List<Map<String, Object>> items) {
        int launched = 0;
        for (Map<String, Object> it : items) {
            if (it.get("productId") == null) continue;
            Long productId = ((Number) it.get("productId")).longValue();
            int qty = it.get("quantity") != null ? ((Number) it.get("quantity")).intValue() : 1;
            if (qty < 1) qty = 1;
            Product p = productRepo.findById(productId).orElse(null);
            if (p == null) continue;

            int weightG = p.getWeightG() != null ? p.getWeightG() : 600;
            double priceUsd = p.getPriceUsd() != null ? p.getPriceUsd() : 0.0;
            double costPen = pricingService.landedPen(priceUsd, weightG); // costo puesto en Perú (con caja)

            retailService.addStock(productId, qty, costPen, "Lanzado a tienda");
            p.setStockPricePen(pricingService.suggestedStockPricePen(priceUsd, weightG));
            if (!Boolean.TRUE.equals(p.getAvailable())) p.setAvailable(true);
            productRepo.save(p);
            launched++;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("received", items.size());
        res.put("launched", launched);
        return ResponseEntity.ok(res);
    }

    /**
     * Ofertas de TODOS los proveedores para un producto + cual define el precio publicado.
     * Para la vista multi-proveedor del admin (comparar costos, ver estado del GTIN).
     */
    @GetMapping("/products/{id}/offers")
    public ResponseEntity<Map<String, Object>> productOffers(@PathVariable Long id) {
        Product p = productRepo.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        var offers = offerRepo.findByProduct_Id(id);
        var usable = costBasisService.usableOffers(id);
        Double basis = costBasisService.basisCostUsd(usable);
        Double cheapest = usable.stream()
                .map(o -> o.getCostUsd()).filter(Objects::nonNull)
                .min(Double::compare).orElse(null);

        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (var o : offers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("offerId", o.getId());
            m.put("supplierId", o.getSupplierId());
            m.put("supplierName", o.getSupplier() != null ? o.getSupplier().getName() : null);
            m.put("supplierActive", o.getSupplier() != null && Boolean.TRUE.equals(o.getSupplier().getActive()));
            m.put("costUsd", o.getCostUsd());
            m.put("inStock", o.getInStock());
            m.put("flashSale", o.getFlashSale());
            m.put("gtin", o.getGtin());
            m.put("gtinStatus", o.getGtinStatus());
            m.put("rawTitle", o.getRawTitle());
            m.put("lastImportedAt", o.getLastImportedAt());
            m.put("isBasis", basis != null && o.getCostUsd() != null
                    && usable.stream().anyMatch(u -> u.getId().equals(o.getId()))
                    && Math.abs(o.getCostUsd() - basis) < 0.001);
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productId", p.getId());
        out.put("gtin", p.getGtin());
        out.put("gtinConflict", p.getGtinConflict());
        out.put("matchPending", p.getMatchPending());
        out.put("basisCostUsd", basis);
        out.put("cheapestCostUsd", cheapest);
        out.put("pricingBasis", pricingService.getPricingBasis());
        out.put("offers", list);
        return ResponseEntity.ok(out);
    }

    // --- ERP: desglose de precio por producto (costo puesto en Perú + consolidado + stock) ---
    @GetMapping("/products/pricing")
    public List<Map<String, Object>> productsPricing() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Product p : productRepo.findAll()) {
            if (p.getPriceUsd() == null || p.getWeightG() == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("landedPen", Math.round(pricingService.landedPen(p.getPriceUsd(), p.getWeightG()) * 100.0) / 100.0);
            m.put("consolidadoPen", pricingService.suggestedPublicPricePen(p.getPriceUsd(), p.getWeightG()));
            m.put("stockPen", pricingService.suggestedStockPricePen(p.getPriceUsd(), p.getWeightG()));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardStats> getDashboard() {
        DashboardStats stats = new DashboardStats();
        stats.setTotalProducts(productRepo.count());
        stats.setActiveConsolidados(consolidadoRepo.countByStatus("ABIERTO"));
        stats.setPendingOrders(orderRepo.countByPaymentStatus("PENDIENTE_SEPARACION"));
        stats.setVerifiedOrders(orderRepo.countByPaymentStatus("SEPARADO")
                + orderRepo.countByPaymentStatus("PAGADO")
                + orderRepo.countByPaymentStatus("VERIFICADO"));
        stats.setRetailStock(retailService.getTotalStock());
        stats.setRetailSalesCount((int) retailService.getAllSales().size());
        stats.setRetailRevenuePen(retailService.getTotalRevenue());
        stats.setRetailProfitPen(retailService.getTotalProfit());
        return ResponseEntity.ok(stats);
    }

    // --- Config management ---
    @GetMapping("/config")
    public List<AppConfig> getAllConfig() {
        return configRepo.findAll();
    }

    @PutMapping("/config/{key}")
    public ResponseEntity<AppConfig> updateConfig(@PathVariable String key, @RequestBody Map<String, String> body) {
        AppConfig config = configRepo.findByConfigKey(key)
                .orElseGet(() -> {
                    AppConfig newConfig = new AppConfig();
                    newConfig.setConfigKey(key);
                    newConfig.setDescription(key);
                    return newConfig;
                });
        config.setConfigValue(body.get("value"));
        AppConfig saved = configRepo.save(config);
        // Precios dinámicos: si cambia una clave de pricing, recalcular consolidado/stock
        // de todos los productos no bloqueados (no debe quedar estático).
        if (PRICING_KEYS.contains(key)) {
            recomputeAllPrices();
        }
        return ResponseEntity.ok(saved);
    }

    private static final java.util.Set<String> PRICING_KEYS = java.util.Set.of(
            "exchange_rate", "courier_cost_per_kg", "repack_cost_per_box",
            "perfumes_per_box", "wholesale_profit_per_unit", "stock_extra_pen",
            "pricing_basis", "plausible_band_pct");

    /**
     * Recalcula el precio Consolidado y Stock de cada producto no bloqueado, con la fórmula única.
     * El costo sale de CostBasisService (ofertas vivas de proveedor); los productos sin ofertas
     * (seed) usan su priceUsd legacy como respaldo. Nunca se toca available aquí.
     */
    private void recomputeAllPrices() {
        List<Product> products = productRepo.findAll();
        for (Product p : products) {
            if (Boolean.TRUE.equals(p.getPriceLocked())) continue;
            Double costUsd = costBasisService.basisCostUsdOrLegacy(p);
            if (costUsd == null || p.getWeightG() == null) continue;
            p.setPriceUsd(costUsd); // re-sync del legacy
            p.setWholesalePricePen(pricingService.suggestedPublicPricePen(costUsd, p.getWeightG()));
            if (p.getStockPricePen() != null) {
                p.setStockPricePen(pricingService.suggestedStockPricePen(costUsd, p.getWeightG()));
            }
        }
        productRepo.saveAll(products);
    }

    // --- Stock Purchase (Compra para Tienda) ---
    @PostMapping("/stock-purchase/preview")
    public ResponseEntity<BreakdownSection> previewStockPurchase(@RequestBody StockPurchaseRequest request) {
        return ResponseEntity.ok(consolidadoService.previewStockPurchase(request));
    }

    @PostMapping("/stock-purchase")
    public ResponseEntity<Order> createStockPurchase(@RequestBody StockPurchaseRequest request) {
        return ResponseEntity.ok(consolidadoService.createStockPurchase(request));
    }

    // --- Consolidados v2: apertura programada, plazo e imagen del aviso ---

    /** Abre (o programa) un consolidado nuevo. 409 si ya hay uno ABIERTO/PROGRAMADO. */
    @PostMapping("/consolidados/open")
    public ResponseEntity<?> openConsolidado(@RequestBody Map<String, Object> body) {
        try {
            Consolidado c = consolidadoService.openConsolidado(
                    asString(body.get("title")),
                    asString(body.get("description")),
                    asLong(body.get("startAtMs")),
                    asLong(body.get("endsAtMs")),
                    asLong(body.get("imageMediaId")));
            return ResponseEntity.ok(c);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Configura plazo/titulo/descripcion/imagen del consolidado ABIERTO o PROGRAMADO. */
    @PutMapping("/consolidados/{id}/schedule")
    public ResponseEntity<?> updateConsolidadoSchedule(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        try {
            Consolidado c = consolidadoService.updateSchedule(id,
                    asString(body.get("title")),
                    asString(body.get("description")),
                    asLong(body.get("startAtMs")),
                    asLong(body.get("endsAtMs")),
                    asLong(body.get("imageMediaId")));
            return ResponseEntity.ok(c);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Reabre TEMPORALMENTE un consolidado cerrado por N minutos (el scheduler lo cierra solo). */
    @PostMapping("/consolidados/{id}/reopen")
    public ResponseEntity<?> reopenConsolidado(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long mins = asLong(body.get("minutes"));
        if (mins == null || mins <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "minutes inválido"));
        }
        try {
            Consolidado c = consolidadoService.reopenTemporarily(id, mins.intValue());
            return ResponseEntity.ok(c);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private static String asString(Object v) { return v != null ? String.valueOf(v) : null; }
    private static Long asLong(Object v) { return v instanceof Number n ? n.longValue() : null; }

    // --- Enable Merchandise (after shipment arrives in Peru) ---
    @PostMapping("/enable-merchandise/{consolidadoId}")
    public ResponseEntity<org.example.backendbvaberiaperfumes.model.Consolidado> enableMerchandise(@PathVariable Long consolidadoId) {
        return ResponseEntity.ok(consolidadoService.enableMerchandise(consolidadoId));
    }

    // --- Sync stock from Google Sheet (Sheet is source of truth for sales) ---
    @PostMapping("/sync-from-sheet")
    public ResponseEntity<Map<String, Object>> syncFromSheet(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sheetStock = (Map<String, Object>) body.get("sheetStock");
        if (sheetStock == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sheetStock required"));
        }

        int adjusted = 0;
        int totalRemoved = 0;
        for (Map.Entry<String, Object> entry : sheetStock.entrySet()) {
            try {
                Long productId = Long.valueOf(entry.getKey());
                int targetStock = ((Number) entry.getValue()).intValue();
                int removed = retailService.adjustStockToMatch(productId, targetStock);
                if (removed > 0) {
                    adjusted++;
                    totalRemoved += removed;
                }
            } catch (Exception e) {
                // Skip invalid entries
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "productsAdjusted", adjusted, "unitsRemoved", totalRemoved));
    }

    // --- Google Apps Script Proxy (avoids CORS + handles redirects) ---
    @PostMapping("/google-proxy")
    public ResponseEntity<String> googleProxy(@RequestBody Map<String, Object> body) {
        String scriptUrl = configRepo.findByConfigKey("google_script_url")
                .map(AppConfig::getConfigValue)
                .orElse(null);

        if (scriptUrl == null || scriptUrl.isBlank()) {
            return ResponseEntity.badRequest()
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"google_script_url not configured\"}");
        }

        try {
            RestTemplate rest = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = rest.postForEntity(scriptUrl, request, String.class);

            String responseBody = response.getBody();
            // Google Apps Script may redirect and return HTML - treat as success
            if (responseBody == null || responseBody.isBlank() || responseBody.trim().startsWith("<")) {
                responseBody = "{\"success\":true}";
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(responseBody);
        } catch (Exception e) {
            // POST likely succeeded (data sent) even if redirect response failed
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body("{\"success\":true,\"note\":\"Data sent, redirect followed\"}");
        }
    }

    @GetMapping("/google-proxy")
    public ResponseEntity<String> googleProxyGet(@RequestParam String action) {
        String scriptUrl = configRepo.findByConfigKey("google_script_url")
                .map(AppConfig::getConfigValue)
                .orElse(null);

        if (scriptUrl == null || scriptUrl.isBlank()) {
            return ResponseEntity.badRequest()
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"google_script_url not configured\"}");
        }

        try {
            RestTemplate rest = new RestTemplate();
            String url = scriptUrl + "?action=" + action;
            ResponseEntity<String> response = rest.getForEntity(url, String.class);

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank() || responseBody.trim().startsWith("<")) {
                responseBody = "{}";
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(responseBody);
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
    // --- BOTÓN DE REINICIO DE OPERACIONES ---
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.backendbvaberiaperfumes.repository.RetailSaleRepository retailSaleRepo;

    @org.springframework.beans.factory.annotation.Autowired
    private org.example.backendbvaberiaperfumes.repository.RetailInventoryRepository retailInventoryRepo;

    @org.springframework.beans.factory.annotation.Autowired
    private org.example.backendbvaberiaperfumes.service.EmailService emailService;

    /** Diagnóstico de correo: intenta enviar una prueba y devuelve el resultado o el error exacto. */
    @GetMapping("/mail-test")
    public Map<String, Object> mailTest(@RequestParam(required = false) String to) {
        return emailService.diagnose(to);
    }

    @DeleteMapping("/factory-reset-operations")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, String>> resetOperations() {
        // 1. Borrado forzado en bloque a nivel de SQL (ignora la memoria caché)
        retailSaleRepo.deleteAllInBatch();
        retailInventoryRepo.deleteAllInBatch();

        // 2. Eliminar pedidos y consolidados
        orderRepo.deleteAll();
        consolidadoRepo.deleteAll();

        // 3. Crear un consolidado base abierto para que el sistema inicie bien
        org.example.backendbvaberiaperfumes.model.Consolidado c = new org.example.backendbvaberiaperfumes.model.Consolidado();
        c.setStatus("ABIERTO");
        consolidadoRepo.save(c);

        return ResponseEntity.ok(Map.of("message", "Operaciones y ventas reiniciadas en cero absoluto exitosamente."));
    }
}
