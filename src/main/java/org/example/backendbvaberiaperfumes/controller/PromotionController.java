package org.example.backendbvaberiaperfumes.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.backendbvaberiaperfumes.dto.PromotionRequest;
import org.example.backendbvaberiaperfumes.model.Promotion;
import org.example.backendbvaberiaperfumes.service.PromotionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class PromotionController {

    /** Ganancia interna del pack: solo la ven las rutas admin (/api/admin/promotions), nunca la tienda. */
    static final String PROFIT_FIELD = "profitPen";

    private final PromotionService promotionService;
    private final ObjectMapper json;

    public PromotionController(PromotionService promotionService, ObjectMapper json) {
        this.promotionService = promotionService;
        this.json = json;
    }

    // ---------- Público (tienda) ----------
    // Mismo JSON que la entidad MENOS profitPen (con precio y ganancia se deduce el costo puesto en Peru).
    @GetMapping("/api/promotions/active")
    public ArrayNode activeForStore() {
        ArrayNode out = json.createArrayNode();
        for (Promotion p : promotionService.activeForStore()) out.add(publicView(p));
        return out;
    }

    @GetMapping("/api/promotions/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            Promotion promo = promotionService.getById(id);
            // Gate NSO: al cliente anonimo, una promo con un perfume que ya no se vende no existe (el admin la ve).
            if (!isAdminRequest() && !promotionService.isPublicPromo(promo)) {
                return ResponseEntity.status(404).body(Map.of("message", "Promoción no encontrada: " + id));
            }
            return ResponseEntity.ok(publicView(promo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    /** La promo como la ve la tienda: la entidad serializada sin la ganancia. */
    private JsonNode publicView(Promotion promo) {
        JsonNode node = json.valueToTree(promo);
        if (node instanceof ObjectNode o) o.remove(PROFIT_FIELD);
        return node;
    }

    private boolean isAdminRequest() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
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
