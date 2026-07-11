package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.ImageSearchRequest;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.ImageCacheRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.service.ApifyImageService;
import org.example.backendbvaberiaperfumes.service.ImageEnrichService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Proxy admin hacia Apify (con caché por UPC): busca fotos, ajustes (token/nº resultados) y faltantes. */
@RestController
@RequestMapping("/api/admin/apify")
public class ApifyController {

    private final ImageEnrichService enrich;
    private final ImageCacheRepository cacheRepo;
    private final ApifyImageService apify;
    private final AppConfigRepository configRepo;
    private final ProductRepository productRepo;
    private final SupplierOfferRepository offerRepo;

    public ApifyController(ImageEnrichService enrich, ImageCacheRepository cacheRepo, ApifyImageService apify,
                           AppConfigRepository configRepo, ProductRepository productRepo,
                           SupplierOfferRepository offerRepo) {
        this.enrich = enrich;
        this.cacheRepo = cacheRepo;
        this.apify = apify;
        this.configRepo = configRepo;
        this.productRepo = productRepo;
        this.offerRepo = offerRepo;
    }

    /**
     * Body: { "items": [ { "idx": 0, "upc": "036...", "query": "marca nombre 100ml perfume" }, ... ] }
     * Respuesta: { "0": "https://...", "5": "https://..." }  (idx -> imageUrl; cache-hits + frescas).
     */
    @PostMapping("/images")
    public ResponseEntity<?> images(@RequestBody ImageSearchRequest req) {
        try {
            Map<Integer, String> res = enrich.enrich(req != null ? req.items : null);
            return ResponseEntity.ok(res);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error consultando Apify: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /** Limpia el caché de imágenes (para re-buscar desde cero, ej. tras mejorar la búsqueda). */
    @DeleteMapping("/cache")
    public ResponseEntity<?> clearCache() {
        long n = cacheRepo.count();
        cacheRepo.deleteAll();
        return ResponseEntity.ok(Map.of("cleared", n, "message", "Caché de imágenes limpiado: " + n + " entradas."));
    }

    /** Ajustes actuales (para la UI): nº de resultados y si hay token configurado. */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> m = new HashMap<>();
        m.put("results", apify.effectiveResults());
        m.put("hasToken", apify.hasToken());
        return m;
    }

    /** Guardar ajustes: nº de resultados y/o token nuevo (para cambiar de cuenta Apify). */
    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> body) {
        if (body.get("results") != null) {
            try {
                int r = Integer.parseInt(String.valueOf(body.get("results")).trim().replaceAll("[^0-9]", ""));
                if (r >= 1 && r <= 30) setConfig("apify_image_results", String.valueOf(r), "Resultados por perfume al buscar foto en Apify");
            } catch (NumberFormatException ignored) {}
        }
        if (body.get("token") != null) {
            String t = String.valueOf(body.get("token")).trim();
            if (!t.isBlank()) setConfig("apify_token", t, "Token de Apify (override del env; para cambiar de cuenta)");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("results", apify.effectiveResults());
        m.put("hasToken", apify.hasToken());
        m.put("message", "Ajustes de Apify guardados.");
        return ResponseEntity.ok(m);
    }

    /** Productos del catálogo (de un proveedor) que NO tienen foto — para rellenarlas. */
    @GetMapping("/missing")
    public List<Map<String, Object>> missing(@RequestParam Long supplierId) {
        List<Long> pids = offerRepo.findProductIdsBySupplier(supplierId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : productRepo.findAllById(pids)) {
            boolean noImg = p.getImageUrl() == null || p.getImageUrl().isBlank();
            if (!noImg || Boolean.TRUE.equals(p.getArchived())) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("brand", p.getBrand());
            m.put("name", p.getName());
            m.put("ml", p.getMl());
            m.put("upc", p.getGtin());
            out.add(m);
        }
        return out;
    }

    private void setConfig(String key, String value, String desc) {
        AppConfig c = configRepo.findByConfigKey(key).orElseGet(() -> new AppConfig(key, value, desc));
        c.setConfigValue(value);
        if (c.getDescription() == null) c.setDescription(desc);
        configRepo.save(c);
    }
}
