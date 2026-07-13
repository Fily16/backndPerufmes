package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.ImageSearchRequest;
import org.example.backendbvaberiaperfumes.model.ImageCache;
import org.example.backendbvaberiaperfumes.repository.ImageCacheRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Rellena fotos usando primero el CACHÉ por UPC y solo llamando a Apify por lo que falta.
 * Cada foto fresca se guarda en el caché, así ninguna llamada se desperdicia: aunque te
 * quedes sin créditos a la mitad, refresques o llegue otro proveedor con el mismo UPC,
 * lo ya buscado se sirve del caché (0 llamadas) y nunca se empieza de cero.
 */
@Service
public class ImageEnrichService {

    private final ApifyImageService apify;
    private final ImageCacheRepository cacheRepo;

    public ImageEnrichService(ApifyImageService apify, ImageCacheRepository cacheRepo) {
        this.apify = apify;
        this.cacheRepo = cacheRepo;
    }

    @Transactional
    public Map<Integer, String> enrich(List<ImageSearchRequest.Item> items) throws Exception {
        return enrich(items, null);
    }

    @Transactional
    public Map<Integer, String> enrich(List<ImageSearchRequest.Item> items, String source) throws Exception {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) return result;

        // 1. Clave de caché por fila.
        Map<Integer, String> keyByIdx = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        for (ImageSearchRequest.Item it : items) {
            String key = cacheKey(it);
            if (key == null) continue;
            keyByIdx.put(it.idx, key);
            keys.add(key);
        }

        // 2. Cargar el caché de golpe.
        Map<String, String> cache = new HashMap<>();
        if (!keys.isEmpty()) {
            for (ImageCache c : cacheRepo.findByCacheKeyIn(keys)) {
                if (c.getImageUrl() != null) cache.put(c.getCacheKey(), c.getImageUrl());
            }
        }

        // 3. Resolver: cache-hit -> resultado directo; miss -> a Apify.
        Map<Integer, String> missQueries = new LinkedHashMap<>();
        for (ImageSearchRequest.Item it : items) {
            String key = keyByIdx.get(it.idx);
            String hit = key != null ? cache.get(key) : null;
            if (hit != null) {
                result.put(it.idx, hit);
            } else if (it.query != null && !it.query.isBlank()) {
                missQueries.put(it.idx, it.query.trim());
            }
        }

        // 4. Llamar a Apify solo por lo que falta (Bing / Fragrantica / Google Images), y GUARDAR en caché.
        if (!missQueries.isEmpty()) {
            Map<Integer, String> fresh;
            if ("bing".equalsIgnoreCase(source)) fresh = apify.fetchBingImages(missQueries);
            else if ("fragrantica".equalsIgnoreCase(source)) fresh = apify.fetchFragranticaImages(missQueries);
            else fresh = apify.fetchImages(missQueries);
            for (Map.Entry<Integer, String> e : fresh.entrySet()) {
                result.put(e.getKey(), e.getValue());
                String key = keyByIdx.get(e.getKey());
                if (key != null) upsert(key, e.getValue());
            }
        }
        return result;
    }

    private String cacheKey(ImageSearchRequest.Item it) {
        if (it.upc != null && !it.upc.isBlank()) return it.upc.trim();
        if (it.query != null && !it.query.isBlank()) return "Q:" + it.query.trim().toLowerCase();
        return null;
    }

    private void upsert(String key, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return;
        ImageCache c = cacheRepo.findByCacheKey(key).orElseGet(() -> new ImageCache(key, imageUrl));
        c.setImageUrl(imageUrl);
        c.setFetchedAt(java.time.LocalDateTime.now());
        cacheRepo.save(c);
    }
}
