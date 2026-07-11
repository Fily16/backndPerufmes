package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.ImageSearchRequest;
import org.example.backendbvaberiaperfumes.repository.ImageCacheRepository;
import org.example.backendbvaberiaperfumes.service.ImageEnrichService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Proxy admin hacia Apify (con caché por UPC): busca fotos de perfumes por nombre. */
@RestController
@RequestMapping("/api/admin/apify")
public class ApifyController {

    private final ImageEnrichService enrich;
    private final ImageCacheRepository cacheRepo;

    public ApifyController(ImageEnrichService enrich, ImageCacheRepository cacheRepo) {
        this.enrich = enrich;
        this.cacheRepo = cacheRepo;
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
}
