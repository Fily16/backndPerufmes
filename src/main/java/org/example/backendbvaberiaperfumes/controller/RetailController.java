package org.example.backendbvaberiaperfumes.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.RetailInventory;
import org.example.backendbvaberiaperfumes.model.RetailSale;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.service.RetailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retail")
public class RetailController {

    private final RetailService retailService;
    private final AppConfigRepository configRepo;

    // Auto-sync: rate limit to max once per 60 seconds
    private volatile long lastSheetSync = 0;
    private static final long SHEET_SYNC_INTERVAL_MS = 60_000;

    public RetailController(RetailService retailService, AppConfigRepository configRepo) {
        this.retailService = retailService;
        this.configRepo = configRepo;
    }

    // --- Public: stock levels for catalog (auto-syncs from Google Sheet) ---
    @GetMapping("/stock")
    public Map<Long, Integer> getRetailStock() {
        syncFromSheetIfNeeded();
        return retailService.getStockByProduct();
    }

    /**
     * Auto-sync stock from Google Sheet.
     * Reads the Sheet's current stock and adjusts backend inventory to match.
     * Rate-limited to max once per minute to avoid spamming Google.
     * Fails silently if Sheet is not configured or unreachable.
     */
    private void syncFromSheetIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastSheetSync < SHEET_SYNC_INTERVAL_MS) return;
        lastSheetSync = now;

        try {
            String scriptUrl = configRepo.findByConfigKey("google_script_url")
                    .map(AppConfig::getConfigValue).orElse(null);
            if (scriptUrl == null || scriptUrl.isBlank()) return;

            RestTemplate rest = new RestTemplate();
            ResponseEntity<String> response = rest.getForEntity(
                    scriptUrl + "?action=getSheetStock", String.class);
            String body = response.getBody();
            if (body == null || body.trim().startsWith("<")) return;

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> sheetStock = mapper.readValue(body,
                    new TypeReference<Map<String, Object>>() {});

            for (Map.Entry<String, Object> entry : sheetStock.entrySet()) {
                try {
                    Long productId = Long.valueOf(entry.getKey());
                    int target = ((Number) entry.getValue()).intValue();
                    retailService.adjustStockToMatch(productId, target);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // Sheet sync failed silently - return backend stock as-is
        }
    }

    // --- Inventory ---
    @GetMapping("/inventory")
    public List<RetailInventory> getAllInventory(@RequestParam(required = false, defaultValue = "false") boolean inStock) {
        return inStock ? retailService.getInStockInventory() : retailService.getAllInventory();
    }

    @PostMapping("/inventory")
    public ResponseEntity<RetailInventory> addStock(@RequestBody Map<String, Object> body) {
        Long productId = Long.valueOf(body.get("productId").toString());
        int quantity = Integer.parseInt(body.get("quantity").toString());
        Double costPerUnit = body.get("costPerUnitPen") != null
                ? Double.parseDouble(body.get("costPerUnitPen").toString()) : null;
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;

        RetailInventory inv = retailService.addStock(productId, quantity, costPerUnit, notes);
        return ResponseEntity.ok(inv);
    }

    // --- Sales ---
    @GetMapping("/sales")
    public List<RetailSale> getAllSales() {
        return retailService.getAllSales();
    }

    @PostMapping("/sales")
    public ResponseEntity<RetailSale> registerSale(@RequestBody Map<String, Object> body) {
        Long productId = Long.valueOf(body.get("productId").toString());
        int quantity = body.get("quantity") != null ? Integer.parseInt(body.get("quantity").toString()) : 1;
        double salePrice = Double.parseDouble(body.get("salePricePen").toString());
        String channel = body.get("channel") != null ? body.get("channel").toString() : "WHATSAPP";

        RetailSale sale = retailService.registerSale(productId, quantity, salePrice, channel);
        return ResponseEntity.ok(sale);
    }

    // --- Public endpoint for Google Apps Script form-sale callback ---
    @PostMapping("/form-sale")
    public ResponseEntity<Map<String, Object>> registerFormSale(@RequestBody Map<String, Object> body) {
        // Validate API key
        String apiKey = body.get("apiKey") != null ? body.get("apiKey").toString() : "";
        String expectedKey = configRepo.findByConfigKey("form_sale_api_key")
                .map(AppConfig::getConfigValue)
                .orElse("");

        if (expectedKey.isEmpty() || !expectedKey.equals(apiKey)) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid API key"));
        }

        try {
            Long productId = Long.valueOf(body.get("productId").toString());
            int quantity = body.get("quantity") != null ? Integer.parseInt(body.get("quantity").toString()) : 1;
            double salePrice = Double.parseDouble(body.get("salePricePen").toString());
            String channel = body.get("channel") != null ? body.get("channel").toString() : "FORMULARIO";

            RetailSale sale = retailService.registerSale(productId, quantity, salePrice, channel);
            return ResponseEntity.ok(Map.of("success", true, "saleId", sale.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
