package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.backendbvaberiaperfumes.controller.ApifyController;
import org.example.backendbvaberiaperfumes.dto.ImageCandidate;
import org.example.backendbvaberiaperfumes.dto.ImageSearchRequest;
import org.example.backendbvaberiaperfumes.model.ImageCache;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.ImageCacheRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.service.ApifyImageService;
import org.example.backendbvaberiaperfumes.service.ImageEnrichService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fotos v2: el ranking de candidatas es la única fuente de la "mejor" foto, el caché
 * conserva el ranking para la revisión visual sin gastar Apify, y la elección manual
 * queda persistida en producto + caché. La detección de rotas vive en el navegador
 * (frontend), por eso aquí solo se prueba la parte de datos.
 */
@SpringBootTest
class ApifyCandidatesTest {

    @Autowired private ApifyImageService apify;
    @Autowired private ImageEnrichService enrich;
    @Autowired private ImageCacheRepository cacheRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private ApifyController controller;

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode result(String url, String origin, String title) {
        ObjectNode n = om.createObjectNode();
        n.put("imageUrl", url);
        n.put("origin", origin);
        n.put("title", title);
        return n;
    }

    /** Idempotente: la H2 de archivo persiste entre corridas, así que reusa la fila si ya existe. */
    private Product newProduct(String sku, String gtin, String imageUrl, boolean archived) {
        Product p = productRepo.findBySku(sku).orElseGet(Product::new);
        p.setSku(sku);
        p.setBrand("MarcaTest");
        p.setName("Foto Test " + sku);
        p.setMl(100);
        p.setWeightG(600);
        p.setAvailable(true);
        p.setArchived(archived);
        p.setGtin(gtin);
        p.setImageUrl(imageUrl);
        return productRepo.save(p);
    }

    /** Idempotente: reemplaza la entrada del caché si quedó de una corrida anterior. */
    private ImageCache freshCache(String key, String imageUrl, String candidatesJson) {
        ImageCache c = cacheRepo.findByCacheKey(key).orElseGet(() -> new ImageCache(key, imageUrl));
        c.setImageUrl(imageUrl);
        c.setCandidatesJson(candidatesJson);
        return cacheRepo.save(c);
    }

    @Test
    void rankFiltraBloqueadosDedupYOrdenaPorScore() {
        List<JsonNode> results = List.of(
                result("https://a.com/1.jpg", "reddit.com", "lattafa khamrah perfume"), // origen bloqueado
                result("https://a.com/2.jpg", "shop.com", "random stuff"),               // 0 coincidencias: basura
                result("https://a.com/3.jpg", "shop.com", "lattafa khamrah eau"),        // score 2
                result("https://a.com/4.jpg", "store.com", "khamrah bottle"),            // score 1
                result("https://a.com/3.jpg", "dup.com", "lattafa khamrah")              // URL duplicada
        );
        List<ImageCandidate> ranked = apify.rankCandidates("lattafa khamrah 100ml perfume", results);
        assertEquals(2, ranked.size());
        assertEquals("https://a.com/3.jpg", ranked.get(0).url);
        assertEquals("https://a.com/4.jpg", ranked.get(1).url);
        assertTrue(ranked.get(0).score > ranked.get(1).score);
    }

    @Test
    void cacheViejoSirveUnaSolaCandidataSinLlamarApify() throws Exception {
        freshCache("TESTKEY-LEGACY-1", "https://img.example/x.jpg", null);
        ImageSearchRequest.Item it = new ImageSearchRequest.Item();
        it.idx = 7;
        it.upc = "TESTKEY-LEGACY-1";
        it.query = "marca nombre 100ml perfume";
        // Cache-hit total: no queda ningún miss, así que jamás toca Apify (sin token lanzaría).
        Map<Integer, List<ImageCandidate>> res = enrich.enrichCandidates(List.of(it), "bing", false);
        assertEquals(1, res.get(7).size());
        assertEquals("https://img.example/x.jpg", res.get(7).get(0).url);
    }

    @Test
    void cacheConRankingHaceRoundtripCompleto() throws Exception {
        List<ImageCandidate> cands = List.of(
                new ImageCandidate("https://a/1.jpg", "shop.com", "titulo 1", 3),
                new ImageCandidate("https://a/2.jpg", "store.com", "titulo 2", 1));
        freshCache("TESTKEY-RANK-1", "https://a/1.jpg", om.writeValueAsString(cands));

        ImageSearchRequest.Item it = new ImageSearchRequest.Item();
        it.idx = 3;
        it.upc = "TESTKEY-RANK-1";
        it.query = "algo";
        Map<Integer, List<ImageCandidate>> res = enrich.enrichCandidates(List.of(it), null, false);
        assertEquals(2, res.get(3).size());
        assertEquals("https://a/1.jpg", res.get(3).get(0).url);
        assertEquals(1, res.get(3).get(1).score);
        assertEquals("store.com", res.get(3).get(1).origin);
    }

    @Test
    void chooseActualizaProductoYDejaLaEleccionEnCache() {
        Product p = newProduct("TEST-FOTO-CHOOSE", "99991234500001", "imagenes/rota-local.jpg", false);
        ResponseEntity<?> resp = controller.choose(Map.of(
                "productId", p.getId(), "imageUrl", "https://nueva.example/elegida.jpg"));
        assertEquals(200, resp.getStatusCode().value());

        Product reloaded = productRepo.findById(p.getId()).orElseThrow();
        assertEquals("https://nueva.example/elegida.jpg", reloaded.getImageUrl());
        ImageCache cached = cacheRepo.findByCacheKey("99991234500001").orElseThrow();
        assertEquals("https://nueva.example/elegida.jpg", cached.getImageUrl());
    }

    @Test
    void photosSinProveedorCubreTodoElCatalogoYFiltraArchivadosYSinFoto() {
        Product con = newProduct("TEST-FOTO-CON", null, "imagenes/local-quiza-rota.jpg", false);
        Product arch = newProduct("TEST-FOTO-ARCH", null, "https://x/i.jpg", true);
        Product sin = newProduct("TEST-FOTO-SIN", null, null, false);

        Set<Object> photoIds = new HashSet<>();
        for (Map<String, Object> r : controller.photos(null)) photoIds.add(r.get("id"));
        assertTrue(photoIds.contains(con.getId()), "producto con foto (aunque sea ruta local) debe escanearse");
        assertFalse(photoIds.contains(arch.getId()), "archivado no se escanea");
        assertFalse(photoIds.contains(sin.getId()), "sin foto no va en photos");

        Set<Object> missingIds = new HashSet<>();
        for (Map<String, Object> r : controller.missing(null)) missingIds.add(r.get("id"));
        assertTrue(missingIds.contains(sin.getId()));
        assertFalse(missingIds.contains(con.getId()));
    }
}
