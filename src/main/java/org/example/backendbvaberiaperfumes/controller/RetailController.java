package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.RetailInventory;
import org.example.backendbvaberiaperfumes.model.RetailSale;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.service.RetailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retail")
public class RetailController {

    private final RetailService retailService;
    private final AppConfigRepository configRepo;

    public RetailController(RetailService retailService, AppConfigRepository configRepo) {
        this.retailService = retailService;
        this.configRepo = configRepo;
    }

    // --- Public: stock levels for catalog ---
    @GetMapping("/stock")
    public Map<Long, Integer> getRetailStock() {
        return retailService.getStockByProduct();
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
