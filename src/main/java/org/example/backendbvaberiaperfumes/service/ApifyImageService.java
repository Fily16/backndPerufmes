package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.dto.ImageCandidate;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Busca imagenes de perfumes con Apify (actor google-images-scraper), por NOMBRE.
 * El token va en la variable de entorno APIFY_TOKEN (nunca en el codigo).
 *
 * Clave para que salga la foto BUENA (y no basura de reddit/noticias): pide varios
 * resultados por consulta y ELIGE el mejor: descarta origenes de redes/noticias/spam y
 * puntua por cuantas palabras del nombre del perfume aparecen en el titulo del resultado.
 */
@Service
public class ApifyImageService {

    @Value("${apify.token:}")
    private String token;

    @Value("${apify.image-actor:tnudF2IxzORPhg4r8}")
    private String actor;

    @Value("${apify.fragrantica-actor:UOdNQyn82QjwAFUhc}")
    private String fragranticaActor;

    @Value("${apify.bing-actor:CTnFA60HRTa9UHXl7}")
    private String bingActor;

    /** Cuantos resultados traer por consulta (mas = mas acierto pero mas costo Apify). Configurable. */
    @Value("${apify.image-results:6}")
    private int resultsPerQuery;

    /** Cuantos perfumes por lote al rellenar fotos (una sola corrida del actor). Configurable. */
    @Value("${apify.batch-size:10}")
    private int batchSize;

    /** Palabras genericas de empaque/genero/relleno que NO cuentan como coincidencia de nombre. */
    private static final Set<String> STOP = Set.of(
            "perfume", "ml", "oz", "edt", "edp", "edc", "spray", "men", "women", "woman",
            "ladies", "lady", "male", "unisex", "set", "tester", "refillable", "pour", "homme",
            "femme", "eau", "de", "la", "the", "for", "with", "by", "new", "gift", "psc", "pcs",
            "pc", "deo", "deodorant", "cologne", "parfum", "and");

    /** Origenes que NO son fotos de producto (redes, noticias, foros, spam). */
    private static final List<String> BLOCKED = List.of(
            "reddit.com", "x.com", "twitter.com", "facebook.com", "instagram.com", "tiktok.com",
            "youtube.com", "quora.com", "pinterest.", "findarticles.com", "foter.com",
            "labourecollege.org", "nollymove.com", "behope.com", "billionhands", "wikipedia.org",
            "linkedin.com", "medium.com");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppConfigRepository configRepo;

    public ApifyImageService(AppConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    /** Token efectivo: el de app_config (editable desde la UI) si existe; si no, el del env. */
    private String effToken() {
        String c = configRepo.findByConfigKey("apify_token").map(AppConfig::getConfigValue).orElse(null);
        return (c != null && !c.isBlank()) ? c.trim() : token;
    }

    /** Resultados por perfume: el de app_config (editable desde la UI) si existe; si no, el del env. */
    public int effectiveResults() {
        return configRepo.findByConfigKey("apify_image_results").map(a -> {
            try { return Math.max(1, Math.min(30, Integer.parseInt(a.getConfigValue().trim()))); }
            catch (NumberFormatException e) { return resultsPerQuery; }
        }).orElse(resultsPerQuery);
    }

    /** Cuántos perfumes por lote (config editable desde la UI; si no, el del env). */
    public int effectiveBatchSize() {
        return configRepo.findByConfigKey("apify_batch_size").map(a -> {
            try { return Math.max(1, Math.min(50, Integer.parseInt(a.getConfigValue().trim()))); }
            catch (NumberFormatException e) { return batchSize; }
        }).orElse(batchSize);
    }

    public boolean configured() {
        String t = effToken();
        return t != null && !t.isBlank();
    }
    public boolean hasToken() { return configured(); }

    /**
     * queriesByIdx: idx de fila -> texto de consulta ("marca nombre 100ml ... perfume").
     * Devuelve idx -> imageUrl (solo las filas para las que se encontro una foto relevante).
     * La "mejor" es SIEMPRE la primera del ranking de candidatas (misma logica que la revision visual).
     */
    public Map<Integer, String> fetchImages(Map<Integer, String> queriesByIdx) throws Exception {
        return firstOf(fetchImageCandidates(queriesByIdx));
    }

    /**
     * Igual que fetchImages pero devuelve las N candidatas FINALES por fila (idx -> ranking),
     * ya filtradas de origenes bloqueados y ordenadas por relevancia. N = effectiveResults().
     */
    public Map<Integer, List<ImageCandidate>> fetchImageCandidates(Map<Integer, String> queriesByIdx) throws Exception {
        if (!configured()) {
            throw new IllegalStateException("Falta configurar APIFY_TOKEN en las variables de entorno.");
        }
        if (queriesByIdx == null || queriesByIdx.isEmpty()) return Map.of();

        List<String> queries = new ArrayList<>(new LinkedHashSet<>(queriesByIdx.values()));

        Map<String, Object> input = new HashMap<>();
        input.put("queries", queries);
        input.put("maxResultsPerQuery", effectiveResults());
        String body = mapper.writeValueAsString(input);

        String url = "https://api.apify.com/v2/acts/" + actor
                + "/run-sync-get-dataset-items?token=" + effToken();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(290))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            String b = resp.body();
            throw new RuntimeException("Apify respondio " + resp.statusCode() + ": "
                    + (b == null ? "" : b.substring(0, Math.min(300, b.length()))));
        }

        // Agrupar TODOS los resultados por consulta.
        JsonNode arr = mapper.readTree(resp.body());
        Map<String, List<JsonNode>> byQuery = new LinkedHashMap<>();
        if (arr.isArray()) {
            for (JsonNode it : arr) {
                String q = it.path("query").asText(null);
                if (q != null) byQuery.computeIfAbsent(q, k -> new ArrayList<>()).add(it);
            }
        }

        // Rankear las candidatas de cada consulta.
        Map<String, List<ImageCandidate>> rankedByQuery = new HashMap<>();
        for (Map.Entry<String, List<JsonNode>> e : byQuery.entrySet()) {
            List<ImageCandidate> ranked = rankCandidates(e.getKey(), e.getValue());
            if (!ranked.isEmpty()) rankedByQuery.put(e.getKey(), ranked);
        }

        Map<Integer, List<ImageCandidate>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : queriesByIdx.entrySet()) {
            List<ImageCandidate> ranked = rankedByQuery.get(e.getValue());
            if (ranked != null) out.put(e.getKey(), ranked);
        }
        return out;
    }

    /** idx -> primera candidata del ranking (o nada si la fila no tuvo candidatas). */
    private static Map<Integer, String> firstOf(Map<Integer, List<ImageCandidate>> candidates) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<ImageCandidate>> e : candidates.entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue().get(0).url);
        }
        return out;
    }

    /**
     * Busca en FRAGRANTICA por nombre (actor de búsqueda). Por cada consulta arma la URL
     * https://www.fragrantica.com/search/?query=NOMBRE y toma el resultado cuyo nombre+marca
     * mejor coincide (thumbnailUrl). Devuelve idx -> imageUrl.
     */
    public Map<Integer, String> fetchFragranticaImages(Map<Integer, String> queriesByIdx) throws Exception {
        return firstOf(fetchFragranticaCandidates(queriesByIdx));
    }

    /** Fragrantica con ranking: por perfume, los matches con suficiente coincidencia ordenados por score. */
    public Map<Integer, List<ImageCandidate>> fetchFragranticaCandidates(Map<Integer, String> queriesByIdx) throws Exception {
        if (!configured()) {
            throw new IllegalStateException("Falta configurar APIFY_TOKEN en las variables de entorno.");
        }
        if (queriesByIdx == null || queriesByIdx.isEmpty()) return Map.of();

        // Consultas limpias + URLs de búsqueda únicas.
        Map<Integer, Set<String>> tokensByIdx = new LinkedHashMap<>();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Map.Entry<Integer, String> e : queriesByIdx.entrySet()) {
            String clean = cleanForSearch(e.getValue());
            if (clean.isBlank()) continue;
            tokensByIdx.put(e.getKey(), tokensOf(clean));
            urls.add("https://www.fragrantica.com/search/?query=" + URLEncoder.encode(clean, StandardCharsets.UTF_8));
        }
        if (urls.isEmpty()) return Map.of();

        Map<String, Object> input = new HashMap<>();
        input.put("urls", new ArrayList<>(urls));
        input.put("maxitems", effectiveResults());
        String body = mapper.writeValueAsString(input);

        String url = "https://api.apify.com/v2/acts/" + fragranticaActor
                + "/run-sync-get-dataset-items?token=" + effToken();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(290))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            String b = resp.body();
            throw new RuntimeException("Apify (Fragrantica) respondio " + resp.statusCode() + ": "
                    + (b == null ? "" : b.substring(0, Math.min(300, b.length()))));
        }

        // Resultados: nombre+marca -> tokens, su imagen y su titulo.
        JsonNode arr = mapper.readTree(resp.body());
        List<Object[]> results = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode it : arr) {
                String img = it.path("thumbnailUrl").asText(null);
                if (img == null || img.isBlank()) img = it.path("imageUrl").asText(null);
                if (img == null || img.isBlank()) continue;
                String label = (it.path("brand").asText("") + " " + it.path("name").asText("")).trim();
                results.add(new Object[]{tokensOf(label), img, label});
            }
        }

        // Emparejar cada perfume con TODOS los resultados que superan el umbral, ordenados por score.
        Map<Integer, List<ImageCandidate>> out = new LinkedHashMap<>();
        int max = effectiveResults();
        for (Map.Entry<Integer, Set<String>> e : tokensByIdx.entrySet()) {
            Set<String> qt = e.getValue();
            int need = Math.min(2, Math.max(1, qt.size()));
            List<ImageCandidate> cands = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object[] r : results) {
                @SuppressWarnings("unchecked")
                Set<String> rt = (Set<String>) r[0];
                String img = (String) r[1];
                if (!seen.add(img)) continue;
                int score = 0;
                for (String t : qt) if (rt.contains(t)) score++;
                if (score >= need) cands.add(new ImageCandidate(img, "fragrantica.com", (String) r[2], score));
            }
            cands.sort((a, b) -> Integer.compare(b.score, a.score));
            if (cands.size() > max) cands = new ArrayList<>(cands.subList(0, max));
            if (!cands.isEmpty()) out.put(e.getKey(), cands);
        }
        return out;
    }

    /** Limpia una consulta para buscar en Fragrantica: quita "perfume", tamaño (ml/oz) y ruido. */
    private String cleanForSearch(String q) {
        if (q == null) return "";
        String s = q.toLowerCase()
                .replaceAll("(?i)\\b\\d+(\\.\\d+)?\\s*(ml|oz)\\b", " ")
                .replaceAll("(?i)\\bperfume\\b", " ")
                .replaceAll("\\s+", " ").trim();
        return s;
    }

    /**
     * BING Images (actor HTTP, rapido). El actor recibe UN query por corrida, asi que se lanzan
     * TODAS en paralelo (async) y se toma el primer resultado con imagen de cada una. Ultra rapido.
     * Query = marca + nombre + ml (sin "perfume" ni la unidad), ej. "AURAA DESIRE DESERT DEW 100".
     */
    public Map<Integer, String> fetchBingImages(Map<Integer, String> queriesByIdx) throws Exception {
        return firstOf(fetchBingCandidates(queriesByIdx));
    }

    /** Bing con ranking: candidatas en el orden del buscador (la primera es la que elegía el flujo clásico). */
    public Map<Integer, List<ImageCandidate>> fetchBingCandidates(Map<Integer, String> queriesByIdx) throws Exception {
        if (!configured()) {
            throw new IllegalStateException("Falta configurar APIFY_TOKEN en las variables de entorno.");
        }
        if (queriesByIdx == null || queriesByIdx.isEmpty()) return Map.of();

        int maxResults = Math.max(1, Math.min(5, effectiveResults()));
        String base = "https://api.apify.com/v2/acts/" + bingActor
                + "/run-sync-get-dataset-items?token=" + effToken();

        // Lanzar todas las busquedas en paralelo.
        Map<Integer, CompletableFuture<HttpResponse<String>>> futures = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : queriesByIdx.entrySet()) {
            String q = cleanForBing(e.getValue());
            if (q.isBlank()) continue;
            Map<String, Object> input = new HashMap<>();
            input.put("query", q);
            input.put("maxResults", maxResults);
            String body = mapper.writeValueAsString(input);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            futures.put(e.getKey(), http.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
        }

        Map<Integer, List<ImageCandidate>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, CompletableFuture<HttpResponse<String>>> e : futures.entrySet()) {
            try {
                HttpResponse<String> resp = e.getValue().join();
                if (resp.statusCode() >= 300) continue;
                JsonNode arr = mapper.readTree(resp.body());
                List<ImageCandidate> cands = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                if (arr.isArray()) {
                    for (JsonNode it : arr) {
                        String img = it.path("imageUrl").asText(null);
                        if (img == null || img.isBlank() || !seen.add(img)) continue;
                        String origin = it.path("origin").asText(it.path("hostPageUrl").asText(""));
                        // score descendente por posicion: la primera del buscador manda
                        cands.add(new ImageCandidate(img, origin, it.path("title").asText(""), maxResults - cands.size()));
                        if (cands.size() >= maxResults) break;
                    }
                }
                if (!cands.isEmpty()) out.put(e.getKey(), cands);
            } catch (Exception ignored) { /* omite esa fila */ }
        }
        return out;
    }

    /** Limpia una consulta para Bing: quita "perfume" y la unidad del tamaño (deja el numero). */
    private String cleanForBing(String q) {
        if (q == null) return "";
        return q.replaceAll("(?i)(\\d+(?:\\.\\d+)?)\\s*(ml|oz)\\b", "$1")
                .replaceAll("(?i)\\bperfume\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * De los resultados de una consulta, devuelve las candidatas FINALES ordenadas:
     * descarta origenes de redes/noticias/spam y resultados cuyo titulo no tenga NI UNA
     * palabra del nombre del perfume (misma regla del antiguo pickBest: basura fuera),
     * puntua por coincidencias y ordena por score (estable: a igual score, orden del
     * buscador). Trunca a effectiveResults() — esas son las "N finales" que ve el admin.
     */
    public List<ImageCandidate> rankCandidates(String query, List<JsonNode> results) {
        Set<String> qtokens = tokensOf(query);
        List<ImageCandidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode it : results) {
            String img = it.path("imageUrl").asText(null);
            if (img == null || img.isBlank() || !seen.add(img)) continue;
            String origin = it.path("origin").asText("").toLowerCase();
            if (isBlocked(origin)) continue;
            String rawTitle = it.path("title").asText("");
            String title = norm(rawTitle);
            int score = 0;
            for (String t : qtokens) if (title.contains(t)) score++;
            if (score < 1) continue;
            out.add(new ImageCandidate(img, origin, rawTitle, score));
        }
        out.sort((a, b) -> Integer.compare(b.score, a.score));
        int max = effectiveResults();
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private boolean isBlocked(String origin) {
        for (String b : BLOCKED) if (origin.contains(b)) return true;
        return false;
    }

    private Set<String> tokensOf(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : norm(s).split("\\s+")) {
            if (w.length() < 2) continue;
            if (STOP.contains(w)) continue;
            if (w.matches("\\d+")) continue;
            out.add(w);
        }
        return out;
    }

    private String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
