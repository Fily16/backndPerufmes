package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.PromotionRequest;
import org.example.backendbvaberiaperfumes.model.Promotion;
import org.example.backendbvaberiaperfumes.service.PromotionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    // ---------- Público (tienda) ----------
    @GetMapping("/api/promotions/active")
    public List<Promotion> activeForStore() {
        return promotionService.activeForStore();
    }

    @GetMapping("/api/promotions/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(promotionService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    // ---------- Admin (JWT) ----------
    @GetMapping("/api/admin/promotions")
    public List<Promotion> findAll() {
        return promotionService.findAll();
    }

    @PostMapping("/api/admin/promotions")
    public ResponseEntity<?> create(@RequestBody PromotionRequest req) {
        try {
            return ResponseEntity.ok(promotionService.create(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/api/admin/promotions/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody PromotionRequest req) {
        try {
            return ResponseEntity.ok(promotionService.update(id, req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/api/admin/promotions/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        promotionService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    /** Ganancia sugerida (precio − Σ puesto en Perú) para el editor del admin. */
    @PostMapping("/api/admin/promotions/suggest-profit")
    public ResponseEntity<?> suggestProfit(@RequestBody Map<String, Object> body) {
        double price = body.get("pricePen") != null ? ((Number) body.get("pricePen")).doubleValue() : 0;
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) body.getOrDefault("productIds", List.of());
        List<Long> ids = raw.stream().map(o -> ((Number) o).longValue()).toList();
        Double suggested = promotionService.suggestProfit(price, ids);
        return ResponseEntity.ok(Map.of("suggestedProfitPen", suggested == null ? "" : suggested));
    }
}
