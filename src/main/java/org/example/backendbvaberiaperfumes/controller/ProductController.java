package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.service.PricingService;
import org.example.backendbvaberiaperfumes.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final PricingService pricingService;

    public ProductController(ProductService productService, PricingService pricingService) {
        this.productService = productService;
        this.pricingService = pricingService;
    }

    @GetMapping
    public List<Product> getAll(@RequestParam(required = false) String category,
                                @RequestParam(required = false) String search,
                                @RequestParam(required = false, defaultValue = "false") boolean onlyAvailable) {
        if (search != null && !search.isBlank()) return productService.search(search);
        if (category != null && !category.isBlank()) return productService.getByCategory(category);
        return onlyAvailable ? productService.getAvailableProducts() : productService.getAllProducts();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getById(@PathVariable Long id) {
        return productService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/pricing")
    public ResponseEntity<Map<String, Object>> getProductPricing(@PathVariable Long id) {
        return productService.getById(id).map(product -> {
            Map<String, Object> pricing = new HashMap<>();
            double landedCostUsd = pricingService.calculateLandedCostUsd(
                    product.getPriceUsd(), product.getWeightG());
            double costPen = pricingService.calculateCostPen(landedCostUsd);

            pricing.put("product", product);
            pricing.put("shippingCostUsd", pricingService.calculateShippingCostUsd(product.getWeightG()));
            pricing.put("landedCostUsd", landedCostUsd);
            pricing.put("costPen", costPen);
            pricing.put("suggestedPrice80", pricingService.suggestedPrice(costPen, 80));
            pricing.put("suggestedPrice100", pricingService.suggestedPrice(costPen, 100));
            pricing.put("suggestedPrice120", pricingService.suggestedPrice(costPen, 120));
            pricing.put("exchangeRate", pricingService.getExchangeRate());
            return ResponseEntity.ok(pricing);
        }).orElse(ResponseEntity.notFound().build());
    }

    // --- Admin endpoints ---
    @PostMapping
    public Product create(@RequestBody Product product) {
        return productService.save(product);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable Long id, @RequestBody Product product) {
        return productService.getById(id).map(existing -> {
            if (product.getName() != null) existing.setName(product.getName());
            if (product.getBrand() != null) existing.setBrand(product.getBrand());
            if (product.getPriceUsd() != null) existing.setPriceUsd(product.getPriceUsd());
            if (product.getRetailPricePen() != null) existing.setRetailPricePen(product.getRetailPricePen());
            if (product.getWholesalePricePen() != null) existing.setWholesalePricePen(product.getWholesalePricePen());
            if (product.getMayorPricePen() != null) existing.setMayorPricePen(product.getMayorPricePen());
            if (product.getAvailable() != null) existing.setAvailable(product.getAvailable());
            if (product.getImageUrl() != null) existing.setImageUrl(product.getImageUrl());
            if (product.getDescription() != null) existing.setDescription(product.getDescription());
            if (product.getIsNew() != null) existing.setIsNew(product.getIsNew());
            if (product.getIsHighlighted() != null) existing.setIsHighlighted(product.getIsHighlighted());
            if (product.getWeightG() != null) existing.setWeightG(product.getWeightG());
            if (product.getMl() != null) existing.setMl(product.getMl());
            if (product.getType() != null) existing.setType(product.getType());
            if (product.getCategory() != null) existing.setCategory(product.getCategory());
            return ResponseEntity.ok(productService.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/prices")
    public ResponseEntity<Product> updatePrices(@PathVariable Long id, @RequestBody Map<String, Object> prices) {
        Double retailPricePen = prices.get("retailPricePen") != null ? ((Number) prices.get("retailPricePen")).doubleValue() : null;
        Double wholesalePricePen = prices.get("wholesalePricePen") != null ? ((Number) prices.get("wholesalePricePen")).doubleValue() : null;
        Double mayorPricePen = prices.get("mayorPricePen") != null ? ((Number) prices.get("mayorPricePen")).doubleValue() : null;
        Double priceUsd = prices.get("priceUsd") != null ? ((Number) prices.get("priceUsd")).doubleValue() : null;
        Integer weightG = prices.get("weightG") != null ? ((Number) prices.get("weightG")).intValue() : null;
        Product updated = productService.updatePrices(id, retailPricePen, wholesalePricePen, mayorPricePen, priceUsd, weightG);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
