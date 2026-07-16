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
            Map<Integer, String> res = enrich.enrich(
                    req != null ? req.items : null,
                    req != null ? req.source : null,
                    req != null && req.force);
            return ResponseEntity.ok(res);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error consultando Apify: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /**
     * Igual que /images pero devuelve las N candidatas FINALES por fila (el mismo ranking
     * del algoritmo, para la revisión visual): { "123": [{url,origin,title,score}, ...], ... }
     */
    @PostMapping("/candidates")
    public ResponseEntity<?> candidates(@RequestBody ImageSearchRequest req) {
        try {
            return ResponseEntity.ok(enrich.enrichCandidates(
                    req != null ? req.items : null,
                    req != null ? req.source : null,
                    req != null && req.force));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error consultando Apify: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /**
     * Guarda la imagen elegida (auto o manual) para un producto y la deja cacheada como
     * definitiva para su UPC/consulta — así una re-búsqueda sin force no la pisa.
     */
    @PostMapping("/choose")
    public ResponseEntity<?> choose(@RequestBody Map<String, Object> body) {
        Long productId;
        try { productId = Long.valueOf(String.valueOf(body.get("productId"))); }
        catch (NumberFormatException e) { return ResponseEntity.badRequest().body(Map.of("message", "productId inválido.")); }
        String url = body.get("imageUrl") != null ? String.valueOf(body.get("imageUrl")).trim() : "";
        if (url.isBlank()) return ResponseEntity.badRequest().body(Map.of("message", "Falta imageUrl."));

        Product p = productRepo.findById(productId).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        p.setImageUrl(url);
        productRepo.save(p);
        enrich.rememberChoice(cacheKeyFor(p), url);
        return ResponseEntity.ok(row(p));
    }

    /** Misma clave de caché que usa el flujo de búsqueda: UPC si hay; si no, la consulta estándar. */
    private String cacheKeyFor(Product p) {
        if (p.getGtin() != null && !p.getGtin().isBlank()) return p.getGtin().trim();
        String ml = p.getMl() != null ? p.getMl() + "ml" : "";
        String q = (Objects.toString(p.getBrand(), "") + " " + Objects.toString(p.getName(), "") + " " + ml + " perfume")
                .replaceAll("\\s+", " ").trim();
        return q.isBlank() ? null : "Q:" + q.toLowerCase();
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
        m.put("batch", apify.effectiveBatchSize());
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
        if (body.get("batch") != null) {
            try {
                int b = Integer.parseInt(String.valueOf(body.get("batch")).trim().replaceAll("[^0-9]", ""));
                if (b >= 1 && b <= 50) setConfig("apify_batch_size", String.valueOf(b), "Perfumes por lote al rellenar fotos");
            } catch (NumberFormatException ignored) {}
        }
        if (body.get("token") != null) {
            String t = String.valueOf(body.get("token")).trim();
            if (!t.isBlank()) setConfig("apify_token", t, "Token de Apify (override del env; para cambiar de cuenta)");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("results", apify.effectiveResults());
        m.put("batch", apify.effectiveBatchSize());
        m.put("hasToken", apify.hasToken());
        m.put("message", "Ajustes de Apify guardados.");
        return ResponseEntity.ok(m);
    }

    /** Productos que NO tienen foto — para rellenarlas. Sin supplierId = TODO el catálogo. */
    @GetMapping("/missing")
    public List<Map<String, Object>> missing(@RequestParam(required = false) Long supplierId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : productsFor(supplierId)) {
            boolean noImg = p.getImageUrl() == null || p.getImageUrl().isBlank();
            if (!noImg || Boolean.TRUE.equals(p.getArchived())) continue;
            out.add(row(p));
        }
        return out;
    }

    /**
     * Productos CON foto, para que el NAVEGADOR del admin las valide (la detección de rotas
     * vive en el frontend: es el único juez fiel de "se ve / no se ve").
     * Sin supplierId = TODO el catálogo (antes solo se escaneaba por proveedor y los
     * productos sin oferta de ese proveedor jamás se revisaban).
     */
    @GetMapping("/photos")
    public List<Map<String, Object>> photos(@RequestParam(required = false) Long supplierId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : productsFor(supplierId)) {
            if (Boolean.TRUE.equals(p.getArchived())) continue;
            if (p.getImageUrl() == null || p.getImageUrl().isBlank()) continue;
            out.add(row(p));
        }
        return out;
    }

    private List<Product> productsFor(Long supplierId) {
        if (supplierId == null) return productRepo.findAll();
        return productRepo.findAllById(offerRepo.findProductIdsBySupplier(supplierId));
    }

    private Map<String, Object> row(Product p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("brand", p.getBrand());
        m.put("name", p.getName());
        m.put("ml", p.getMl());
        m.put("upc", p.getGtin());
        m.put("imageUrl", p.getImageUrl());
        return m;
    }

    private void setConfig(String key, String value, String desc) {
        AppConfig c = configRepo.findByConfigKey(key).orElseGet(() -> new AppConfig(key, value, desc));
        c.setConfigValue(value);
        if (c.getDescription() == null) c.setDescription(desc);
        configRepo.save(c);
    }
}
