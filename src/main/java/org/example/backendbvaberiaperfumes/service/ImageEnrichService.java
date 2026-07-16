package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.dto.ImageCandidate;
import org.example.backendbvaberiaperfumes.dto.ImageSearchRequest;
import org.example.backendbvaberiaperfumes.model.ImageCache;
import org.example.backendbvaberiaperfumes.repository.ImageCacheRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Rellena fotos usando primero el CACHÉ por UPC y solo llamando a Apify por lo que falta.
 * Cada búsqueda fresca guarda en el caché la mejor foto Y el ranking completo de candidatas
 * (candidates_json), así la revisión visual reabre las mismas N sin gastar créditos.
 * Filas viejas del caché (solo imageUrl) se sirven como ranking de 1 candidata: con "force"
 * se re-busca y el ranking queda completo.
 */
@Service
public class ImageEnrichService {

    private final ApifyImageService apify;
    private final ImageCacheRepository cacheRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImageEnrichService(ApifyImageService apify, ImageCacheRepository cacheRepo) {
        this.apify = apify;
        this.cacheRepo = cacheRepo;
    }

    @Transactional
    public Map<Integer, String> enrich(List<ImageSearchRequest.Item> items) throws Exception {
        return enrich(items, null, false);
    }

    @Transactional
    public Map<Integer, String> enrich(List<ImageSearchRequest.Item> items, String source) throws Exception {
        return enrich(items, source, false);
    }

    /** Flujo clásico (una URL por fila): SIEMPRE la primera candidata del ranking. */
    @Transactional
    public Map<Integer, String> enrich(List<ImageSearchRequest.Item> items, String source, boolean force) throws Exception {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<ImageCandidate>> e : enrichCandidates(items, source, force).entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue().get(0).url);
        }
        return out;
    }

    /**
     * idx -> las N candidatas finales (ranking del algoritmo). Cache-hit devuelve el ranking
     * guardado sin llamar a Apify; miss (o force) busca, y guarda mejor + ranking.
     */
    @Transactional
    public Map<Integer, List<ImageCandidate>> enrichCandidates(List<ImageSearchRequest.Item> items,
                                                               String source, boolean force) throws Exception {
        Map<Integer, List<ImageCandidate>> result = new LinkedHashMap<>();
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
        Map<String, ImageCache> cache = new HashMap<>();
        if (!keys.isEmpty()) {
            for (ImageCache c : cacheRepo.findByCacheKeyIn(keys)) {
                if (c.getImageUrl() != null) cache.put(c.getCacheKey(), c);
            }
        }

        // 3. Resolver: cache-hit -> ranking guardado (o 1 sola si es fila vieja); miss -> a Apify.
        Map<Integer, String> missQueries = new LinkedHashMap<>();
        for (ImageSearchRequest.Item it : items) {
            String key = keyByIdx.get(it.idx);
            ImageCache hit = (!force && key != null) ? cache.get(key) : null;   // force = ignora el caché
            if (hit != null) {
                List<ImageCandidate> cands = parseCandidates(hit.getCandidatesJson());
                if (cands.isEmpty()) cands = List.of(new ImageCandidate(hit.getImageUrl(), null, null, 0));
                result.put(it.idx, cands);
            } else if (it.query != null && !it.query.isBlank()) {
                missQueries.put(it.idx, it.query.trim());
            }
        }

        // 4. Llamar a Apify solo por lo que falta, y GUARDAR mejor + ranking en el caché.
        if (!missQueries.isEmpty()) {
            Map<Integer, List<ImageCandidate>> fresh;
            if ("bing".equalsIgnoreCase(source)) fresh = apify.fetchBingCandidates(missQueries);
            else if ("fragrantica".equalsIgnoreCase(source)) fresh = apify.fetchFragranticaCandidates(missQueries);
            else fresh = apify.fetchImageCandidates(missQueries);
            for (Map.Entry<Integer, List<ImageCandidate>> e : fresh.entrySet()) {
                List<ImageCandidate> cands = e.getValue();
                if (cands == null || cands.isEmpty()) continue;
                result.put(e.getKey(), cands);
                String key = keyByIdx.get(e.getKey());
                if (key != null) upsert(key, cands.get(0).url, toJson(cands));
            }
        }
        return result;
    }

    /** La elección manual del admin queda cacheada como definitiva para esa clave. */
    @Transactional
    public void rememberChoice(String cacheKey, String imageUrl) {
        if (cacheKey == null || cacheKey.isBlank()) return;
        upsert(cacheKey, imageUrl, null); // null = no tocar el ranking guardado
    }

    private List<ImageCandidate> parseCandidates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ImageCandidate> list = mapper.readValue(json, new TypeReference<List<ImageCandidate>>() { });
            return list != null ? list : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<ImageCandidate> cands) {
        try { return mapper.writeValueAsString(cands); }
        catch (Exception e) { return null; }
    }

    private String cacheKey(ImageSearchRequest.Item it) {
        if (it.upc != null && !it.upc.isBlank()) return it.upc.trim();
        if (it.query != null && !it.query.isBlank()) return "Q:" + it.query.trim().toLowerCase();
        return null;
    }

    private void upsert(String key, String imageUrl, String candidatesJson) {
        if (imageUrl == null || imageUrl.isBlank()) return;
        ImageCache c = cacheRepo.findByCacheKey(key).orElseGet(() -> new ImageCache(key, imageUrl));
        c.setImageUrl(imageUrl);
        if (candidatesJson != null) c.setCandidatesJson(candidatesJson);
        c.setFetchedAt(java.time.LocalDateTime.now());
        cacheRepo.save(c);
    }
}
