package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.*;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.example.backendbvaberiaperfumes.service.PricingService;
import org.example.backendbvaberiaperfumes.service.RetailService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

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

    public AdminController(ProductRepository productRepo, ConsolidadoRepository consolidadoRepo,
                           OrderRepository orderRepo, AppConfigRepository configRepo,
                           RetailService retailService, PricingService pricingService,
                           ConsolidadoService consolidadoService) {
        this.productRepo = productRepo;
        this.consolidadoRepo = consolidadoRepo;
        this.orderRepo = orderRepo;
        this.configRepo = configRepo;
        this.retailService = retailService;
        this.pricingService = pricingService;
        this.consolidadoService = consolidadoService;
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
        return ResponseEntity.ok(configRepo.save(config));
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
}
