package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;

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

    /** Cuantos resultados traer por consulta (mas = mas acierto pero mas costo Apify). Configurable. */
    @Value("${apify.image-results:6}")
    private int resultsPerQuery;

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

    public boolean configured() {
        String t = effToken();
        return t != null && !t.isBlank();
    }
    public boolean hasToken() { return configured(); }

    /**
     * queriesByIdx: idx de fila -> texto de consulta ("marca nombre 100ml ... perfume").
     * Devuelve idx -> imageUrl (solo las filas para las que se encontro una foto relevante).
     */
    public Map<Integer, String> fetchImages(Map<Integer, String> queriesByIdx) throws Exception {
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

        // Elegir la mejor foto de cada consulta.
        Map<String, String> bestByQuery = new HashMap<>();
        for (Map.Entry<String, List<JsonNode>> e : byQuery.entrySet()) {
            String best = pickBest(e.getKey(), e.getValue());
            if (best != null) bestByQuery.put(e.getKey(), best);
        }

        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : queriesByIdx.entrySet()) {
            String img = bestByQuery.get(e.getValue());
            if (img != null) out.put(e.getKey(), img);
        }
        return out;
    }

    /**
     * De los resultados de una consulta, devuelve la imagen mas relevante.
     * Acepta cualquier resultado (no de redes/noticias) cuyo titulo tenga AL MENOS 1 palabra
     * del nombre del perfume; elige el de mayor coincidencia. null solo si todo fue basura.
     */
    private String pickBest(String query, List<JsonNode> results) {
        Set<String> qtokens = tokensOf(query);
        int bestScore = 0; // >=1 coincidencia del nombre en el titulo
        String best = null;
        for (JsonNode it : results) {
            String img = it.path("imageUrl").asText(null);
            if (img == null || img.isBlank()) continue;
            String origin = it.path("origin").asText("").toLowerCase();
            if (isBlocked(origin)) continue;
            String title = norm(it.path("title").asText(""));
            int score = 0;
            for (String t : qtokens) if (title.contains(t)) score++;
            if (score > bestScore) { bestScore = score; best = img; }
        }
        return best;
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
