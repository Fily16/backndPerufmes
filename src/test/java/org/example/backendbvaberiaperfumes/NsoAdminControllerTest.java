package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.service.nso.NsoService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * /api/admin/nso/** por HTTP: seguridad y la forma EXACTA del JSON del contrato (el frontend ya esta construido
 * contra el), incluidos los dos AGREGADOS (POST /catalog re-verifica la marca; POST /api/admin/products crea un
 * perfume con su NSO en una sola operacion).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsoadmincontrollertest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        "spring.mail.password=",
        "resend.api.key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NsoAdminControllerTest {

    static final String CSV = "﻿nso,categoria,marca,producto,ean,titular,ruc\r\n"
            + "NSOC91001-25PE,Arabe,VELMORA,AGUA DE PERFUME-MOONLIGHT ROSE,,IMPORTADORA DEMO SAC,20123456789\r\n"
            + "NSOC91002-25PE,Arabe,VELMORA,AGUA DE TOCADOR-AMBER NIGHT,,IMPORTADORA DEMO SAC,20123456789\r\n"
            + "NSOC91003-24CO,Arabe,VELMORA,AGUA DE PERFUME-SILVER STORM,,,\r\n"
            + "NSOC91004-25PE,Arabe,VELMORA,AGUA DE PERFUME,,IMPORTADORA DEMO SAC,20123456789\r\n"
            + "NSOC12-25PE,Arabe,VELMORA,ROTO,,,\r\n";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NsoService service;
    @Autowired ProductRepository productRepo;

    private static String token;

    // ============================ helpers ============================

    private String auth() throws Exception {
        if (token == null) {
            String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"admin@aromastudio.pe\",\"password\":\"admin123\"}"))
                    .andReturn().getResponse().getContentAsString();
            token = "Bearer " + json.readTree(body).get("token").asText();
        }
        return token;
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder rb) throws Exception {
        return mvc.perform(rb.header("Authorization", auth())).andReturn().getResponse();
    }

    private JsonNode ok(MockHttpServletRequestBuilder rb) throws Exception {
        MockHttpServletResponse r = call(rb);
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode jsonBody(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder rb, String body) {
        return rb.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static Set<String> keys(JsonNode n) {
        Set<String> out = new TreeSet<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static void assertKeys(JsonNode n, String... expected) {
        assertEquals(new TreeSet<>(Set.of(expected)), keys(n), n.toString());
    }

    private Product velmora(String sku, String name) {
        Product p = new Product();
        p.setSku(sku);
        p.setBrand("Velmora");
        p.setName(name);
        p.setMl(100);
        p.setWeightG(600);
        p.setAvailable(true);
        p.setWholesalePricePen(149.0);
        p.setPriceUsd(20.0);
        return productRepo.save(p);
    }

    // ============================ 1. seguridad + catalogo vacio ============================

    @Test
    @Order(1)
    void sinTokenNoEntraYSinListaElGateDa409() throws Exception {
        for (String url : new String[]{"/api/admin/nso/summary", "/api/admin/nso/index", "/api/admin/products"}) {
            int s = mvc.perform(get(url)).andReturn().getResponse().getStatus();
            assertTrue(s == 401 || s == 403, url + " sin token: " + s);
        }
        int up = mvc.perform(multipart("/api/admin/nso/catalog/upload")
                .file(new MockMultipartFile("file", "nso.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8))))
                .andReturn().getResponse().getStatus();
        assertTrue(up == 401 || up == 403, "subir sin token: " + up);

        MockHttpServletResponse r = call(withJson(put("/api/admin/nso/gate"), "{\"enabled\":true}"));
        assertEquals(409, r.getStatus());
        assertKeys(jsonBody(r), "message");
    }

    // ============================ 2. flujo HTTP completo ============================

    @Test
    @Order(2)
    void flujoCompletoConLasFormasDelContrato() throws Exception {
        Product moon = velmora("NSO-HTTP-MOON", "Moonlight Rose 3.4 Oz Edp Women");
        Product amber = velmora("NSO-HTTP-AMBER", "Amber Night 3.4 Oz Edp Men");
        Product dune = velmora("NSO-HTTP-DUNE", "Golden Dune Edp Men");

        // 1. upload
        MockHttpServletResponse bad = call(multipart("/api/admin/nso/catalog/upload")
                .file(new MockMultipartFile("file", "otra.csv", "text/csv", "a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals(400, bad.getStatus());
        assertKeys(jsonBody(bad), "message");

        JsonNode up = ok(multipart("/api/admin/nso/catalog/upload")
                .file(new MockMultipartFile("file", "NSO_perfumes_Peru.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8))));
        assertKeys(up, "fileType", "filename", "recordsRead", "inserted", "updated", "unchanged", "invalidRows",
                "missingFromFile", "researchLinksRead", "catalogVersion", "rematchStarted");
        assertEquals("CSV", up.get("fileType").asText());
        assertEquals(4, up.get("recordsRead").asInt());
        assertEquals(1, up.get("invalidRows").size());
        assertKeys(up.get("invalidRows").get(0), "row", "raw", "reason");
        assertTrue(up.get("rematchStarted").asBoolean());
        assertTrue(service.awaitRematchIdle(60_000));

        // 2. summary
        JsonNode summary = ok(get("/api/admin/nso/summary"));
        // acceptCanCodes: agregado en las aclaraciones de FASE 2 del contrato (lo usa isHidden() del frontend)
        // reviewMinScore: agregado en FASE 3 (se cambia con PUT /settings)
        assertKeys(summary, "gateEnabled", "gateEffective", "acceptCanCodes", "reviewMinScore", "catalogRecords",
                "catalogActiveRecords", "catalogVersion", "lastUpload", "counts", "pendingCandidates", "publicNow",
                "publicIfActivated", "codesNotInLastUpload", "rematch");
        assertTrue(summary.get("acceptCanCodes").isBoolean());
        assertEquals(0.66, summary.get("reviewMinScore").asDouble(), 1e-9);
        assertKeys(summary.get("lastUpload"), "at", "by", "filename");
        assertEquals("admin@aromastudio.pe", summary.get("lastUpload").get("by").asText());
        assertKeys(summary.get("counts"), "CON_NSO", "EN_REVISION", "MARCA_CON_NSO", "SIN_NSO", "SIN_VERIFICAR");
        assertKeys(summary.get("rematch"), "running", "processed", "total", "startedAt", "finishedAt", "error");
        assertTrue(summary.get("counts").get("CON_NSO").asInt() >= 1);

        // 5-6. index y conteo
        JsonNode index = ok(get("/api/admin/nso/index"));
        assertTrue(index.isArray() && index.size() > 0);
        assertKeys(index.get(0), "productId", "status", "nsoCode", "matchedBy", "locked", "score", "country", "nsoYear",
                "otherCanCountry", "possiblyExpired");
        JsonNode count = ok(get("/api/admin/nso/candidates/count"));
        assertKeys(count, "pending");
        assertTrue(count.get("pending").asInt() >= 1);

        // 7. review
        JsonNode review = ok(get("/api/admin/nso/review"));
        JsonNode item = null;
        for (JsonNode i : review) if (i.get("product").get("id").asLong() == amber.getId()) item = i;
        assertNotNull(item, review.toString());
        assertKeys(item, "product", "reasons", "candidates");
        assertKeys(item.get("product"), "id", "brand", "name", "ml", "imageUrl", "gtin", "pricePen", "offers");
        assertKeys(item.get("candidates").get(0), "id", "nsoCode", "brand", "declaredName", "titular", "ruc", "country",
                "nsoYear", "score", "reasons", "origin");
        long candidateId = item.get("candidates").get(0).get("id").asLong();

        // 12. brand-groups
        JsonNode groups = ok(get("/api/admin/nso/brand-groups"));
        assertTrue(groups.size() >= 1, groups.toString());
        assertKeys(groups.get(0), "brandKey", "brandName", "titulares", "genericRecords", "unknownTitularCodes", "products");
        assertKeys(groups.get(0).get("titulares").get(0), "titular", "ruc", "codes");
        assertKeys(groups.get(0).get("products").get(0), "id", "brand", "name", "ml", "pricePen", "imageUrl", "suppliers");

        // 13. products?status=
        JsonNode con = ok(get("/api/admin/nso/products").param("status", "CON_NSO"));
        assertTrue(con.size() >= 1);
        assertKeys(con.get(0), "id", "brand", "name", "ml", "imageUrl", "pricePen", "suppliers", "available", "archived",
                "status", "nsoCode", "declaredName", "titular", "matchedBy", "locked", "score", "reasons",
                "suggestedBrand", "country", "nsoYear", "possiblyExpired", "decidedBy", "decidedAt");
        assertEquals(400, call(get("/api/admin/nso/products").param("status", "NADA")).getStatus());

        // 14. catalog
        JsonNode page = ok(get("/api/admin/nso/catalog").param("q", "").param("page", "0").param("size", "50"));
        assertKeys(page, "items", "total", "page", "size");
        assertEquals(4, page.get("total").asInt());
        assertKeys(page.get("items").get(0), "code", "brand", "declaredName", "titular", "ruc", "tipo", "origen", "country",
                "nsoYear", "source", "active", "inLastUpload", "linkedProducts", "possiblyExpired", "usdKg", "lastImportDate");

        // 8. accept
        JsonNode acc = ok(post("/api/admin/nso/candidates/" + candidateId + "/accept"));
        assertKeys(acc, "productId", "status", "nsoCode", "alsoResolved");
        assertEquals("CON_NSO", acc.get("status").asText());
        assertEquals(409, call(post("/api/admin/nso/candidates/" + candidateId + "/accept")).getStatus());
        assertEquals(404, call(post("/api/admin/nso/candidates/999999/accept")).getStatus());

        // 10-11. assign / unassign
        MockHttpServletResponse formato = call(withJson(post("/api/admin/nso/products/" + dune.getId() + "/assign"), "{\"code\":\"ABC\"}"));
        assertEquals(400, formato.getStatus());
        assertKeys(jsonBody(formato), "message");
        MockHttpServletResponse noExiste = call(withJson(post("/api/admin/nso/products/" + dune.getId() + "/assign"),
                "{\"code\":\"NSOC91999-25PE\"}"));
        assertEquals(404, noExiste.getStatus());
        assertKeys(jsonBody(noExiste), "message", "canCreate");
        assertTrue(jsonBody(noExiste).get("canCreate").asBoolean());
        JsonNode assigned = ok(withJson(post("/api/admin/nso/products/" + dune.getId() + "/assign"),
                "{\"code\":\"NSOC91999-25PE\",\"createIfMissing\":true,\"declaredName\":\"AGUA DE PERFUME-GOLDEN DUNE\"}"));
        assertEquals("CON_NSO", assigned.get("status").asText());
        assertEquals("NSOC91999-25PE", assigned.get("nsoCode").asText());
        JsonNode un = ok(post("/api/admin/nso/products/" + dune.getId() + "/unassign"));
        assertTrue(un.has("productId") && un.has("status"));
        assertNotEquals("CON_NSO", un.get("status").asText());

        // 9. reject-all (sin pendientes: devuelve el estado)
        JsonNode rej = ok(post("/api/admin/nso/products/" + moon.getId() + "/reject-all"));
        assertEquals("CON_NSO", rej.get("status").asText());

        // 4. rematch
        JsonNode sync = ok(withJson(post("/api/admin/nso/rematch"), "{\"productIds\":[" + moon.getId() + "," + dune.getId() + "]}"));
        assertKeys(sync, "processed");
        assertEquals(2, sync.get("processed").asInt());
        MockHttpServletResponse async = call(post("/api/admin/nso/rematch"));
        assertTrue(async.getStatus() == 202 || async.getStatus() == 409, "" + async.getStatus());
        if (async.getStatus() == 202) assertTrue(jsonBody(async).get("started").asBoolean());
        assertTrue(service.awaitRematchIdle(60_000));

        // 15. POST /catalog (AGREGADO): re-verifica la marca al instante
        Product rain = velmora("NSO-HTTP-RAIN", "Crystal Rain Edp Women");
        ok(withJson(post("/api/admin/nso/rematch"), "{\"productIds\":[" + rain.getId() + "]}"));
        JsonNode added = ok(withJson(post("/api/admin/nso/catalog"),
                "{\"code\":\"NSOC91005-25PE\",\"brand\":\"VELMORA\",\"declaredName\":\"AGUA DE PERFUME-CRYSTAL RAIN\",\"titular\":\"IMPORTADORA DEMO SAC\",\"ruc\":\"20123456789\"}"));
        assertKeys(added, "record", "rematched", "linkedProducts");
        assertEquals("NSOC91005-25PE", added.get("record").get("code").asText());
        assertEquals("MANUAL", added.get("record").get("source").asText());
        boolean rainLinked = false;
        for (JsonNode lp : added.get("linkedProducts")) {
            assertKeys(lp, "id", "brand", "name", "status");
            if (lp.get("id").asLong() == rain.getId() && "CON_NSO".equals(lp.get("status").asText())) rainLinked = true;
        }
        assertTrue(rainLinked, added.toString());
        assertEquals(409, call(withJson(post("/api/admin/nso/catalog"),
                "{\"code\":\"NSOC91005-25PE\",\"brand\":\"VELMORA\",\"declaredName\":\"X\"}")).getStatus());
        assertEquals(400, call(withJson(post("/api/admin/nso/catalog"),
                "{\"code\":\"NSOC1-25PE\",\"brand\":\"VELMORA\",\"declaredName\":\"X\"}")).getStatus());

        // 16. activar/desactivar (previsualizar)
        JsonNode prev = ok(withJson(put("/api/admin/nso/catalog/NSOC91001-25PE/active"), "{\"active\":false,\"confirm\":false}"));
        assertKeys(prev, "applied", "affectedProducts");
        assertFalse(prev.get("applied").asBoolean());
        assertKeys(prev.get("affectedProducts").get(0), "id", "brand", "name");

        // 17. brand-aliases
        Product alias = productRepo.save(copyWithBrand(moon, "NSO-HTTP-ALIAS", "Velmora Parfums Paris"));
        JsonNode al = ok(withJson(post("/api/admin/nso/brand-aliases"),
                "{\"supplierBrand\":\"Velmora Parfums Paris\",\"catalogBrandKey\":\"velmora\"}"));
        assertKeys(al, "ok", "rematched");
        assertTrue(al.get("ok").asBoolean());
        assertEquals(400, call(withJson(post("/api/admin/nso/brand-aliases"),
                "{\"supplierBrand\":\"X\",\"catalogBrandKey\":\"marca que no existe\"}")).getStatus());

        // 18. events
        JsonNode events = ok(get("/api/admin/nso/events").param("limit", "50"));
        assertTrue(events.size() > 0);
        assertKeys(events.get(0), "id", "type", "productId", "nsoCode", "fromStatus", "toStatus", "actor", "detail", "createdAt");

        // 3. gate on/off -> summary
        JsonNode on = ok(withJson(put("/api/admin/nso/gate"), "{\"enabled\":true}"));
        assertTrue(on.get("gateEffective").asBoolean());
        JsonNode off = ok(withJson(put("/api/admin/nso/gate"), "{\"enabled\":false}"));
        assertFalse(off.get("gateEnabled").asBoolean());

        // GET /api/admin/products: todo, sin gate; includeArchived
        alias.setArchived(true);
        productRepo.save(alias);
        JsonNode all = ok(get("/api/admin/products"));
        assertEquals(productRepo.countByArchivedFalse(), all.size());
        JsonNode withArchived = ok(get("/api/admin/products").param("includeArchived", "true"));
        assertEquals(productRepo.count(), withArchived.size());
    }

    private static Product copyWithBrand(Product base, String sku, String brand) {
        Product p = new Product();
        p.setSku(sku);
        p.setBrand(brand);
        p.setName(base.getName());
        p.setMl(base.getMl());
        p.setWeightG(base.getWeightG());
        p.setAvailable(true);
        p.setPriceUsd(base.getPriceUsd());
        return p;
    }

    // ============================ 3. POST /api/admin/products (AGREGADO) ============================

    @Test
    @Order(3)
    void altaDePerfumeConSuNsoEnUnaSolaOperacion() throws Exception {
        long antes = productRepo.count();
        String base = "\"sku\":\"NSO-ALTA-HTTP\",\"brand\":\"Velmora\",\"name\":\"Moonlight Rose Edp Women\",\"type\":\"Women EDP\","
                + "\"ml\":100,\"priceUsd\":25.5,\"weightG\":600,\"category\":\"women\",\"available\":true";

        MockHttpServletResponse formato = call(withJson(post("/api/admin/products"), "{" + base + ",\"nso\":{\"code\":\"XX-1\"}}"));
        assertEquals(400, formato.getStatus());
        MockHttpServletResponse falta = call(withJson(post("/api/admin/products"), "{" + base + ",\"nso\":{\"code\":\"NSOC91888-25PE\"}}"));
        assertEquals(404, falta.getStatus());
        assertTrue(jsonBody(falta).get("canCreate").asBoolean());
        assertEquals(antes, productRepo.count(), "no se crea nada si el codigo no valida");

        JsonNode created = ok(withJson(post("/api/admin/products"),
                "{" + base + ",\"nso\":{\"code\":\"NSOC91888-25PE\",\"createIfMissing\":true,\"titular\":\"IMPORTADORA DEMO SAC\",\"ruc\":\"20123456789\"}}"));
        assertKeys(created, "product", "nso");
        assertKeys(created.get("nso"), "productId", "status", "nsoCode");
        assertEquals("CON_NSO", created.get("nso").get("status").asText());
        assertEquals("NSOC91888-25PE", created.get("nso").get("nsoCode").asText());
        assertEquals("NSO-ALTA-HTTP", created.get("product").get("sku").asText());
        assertEquals(25.5, created.get("product").get("priceUsd").asDouble());
        assertEquals(created.get("product").get("id").asLong(), created.get("nso").get("productId").asLong());

        MockHttpServletResponse dup = call(withJson(post("/api/admin/products"), "{" + base + "}"));
        assertEquals(409, dup.getStatus());
        assertKeys(jsonBody(dup), "message");

        JsonNode sinNso = ok(withJson(post("/api/admin/products"),
                "{\"sku\":\"NSO-ALTA-HTTP-2\",\"brand\":\"Marca Nueva\",\"name\":\"Algo\",\"ml\":50,\"available\":true}"));
        assertEquals("SIN_NSO", sinNso.get("nso").get("status").asText());
        assertEquals(400, call(withJson(post("/api/admin/products"), "{\"brand\":\"Velmora\",\"name\":\"Sin SKU\"}")).getStatus());
    }

    // ============================ 4. PUT /settings (FASE 3) ============================

    @Test
    @Order(4)
    void opcionesAceptarOtrosPaisesYUmbralDeRevision() throws Exception {
        assertTrue(service.awaitRematchIdle(60_000));
        int anon = mvc.perform(withJson(put("/api/admin/nso/settings"), "{\"acceptCanCodes\":false}")).andReturn()
                .getResponse().getStatus();
        assertTrue(anon == 401 || anon == 403, "sin token: " + anon);
        for (String bad : new String[]{"{}", "{\"reviewMinScore\":0.3}", "{\"reviewMinScore\":0.99}",
                "{\"acceptCanCodes\":\"no\"}", "{\"reviewMinScore\":\"alto\"}"}) {
            MockHttpServletResponse r = call(withJson(put("/api/admin/nso/settings"), bad));
            assertEquals(400, r.getStatus(), bad + " -> " + r.getContentAsString());
            assertKeys(jsonBody(r), "message");
        }

        // Perfume cuyo unico NSO es de Colombia (NSOC91003-24CO «SILVER STORM»): cuenta mientras se acepten.
        Product storm = velmora("NSO-HTTP-STORM", "Silver Storm Edp Women");
        ok(withJson(post("/api/admin/nso/rematch"), "{\"productIds\":[" + storm.getId() + "]}"));
        JsonNode antes = null;
        for (JsonNode i : ok(get("/api/admin/nso/index"))) if (i.get("productId").asLong() == storm.getId()) antes = i;
        assertNotNull(antes);
        assertEquals("CON_NSO", antes.get("status").asText(), antes.toString());
        assertEquals("NSOC91003-24CO", antes.get("nsoCode").asText());
        assertTrue(antes.get("otherCanCountry").asBoolean());
        int eventosAntes = ok(get("/api/admin/nso/events").param("type", "SETTINGS")).size();

        // Solo Peru: el summary lo refleja, queda en la bitacora y la re-verificacion en segundo plano lo saca de CON_NSO.
        JsonNode soloPeru = ok(withJson(put("/api/admin/nso/settings"), "{\"acceptCanCodes\":false}"));
        assertFalse(soloPeru.get("acceptCanCodes").asBoolean());
        assertTrue(soloPeru.has("reviewMinScore") && soloPeru.has("counts") && soloPeru.has("rematch"), soloPeru.toString());
        assertTrue(service.awaitRematchIdle(60_000));
        JsonNode despues = null;
        for (JsonNode i : ok(get("/api/admin/nso/index"))) if (i.get("productId").asLong() == storm.getId()) despues = i;
        assertNotNull(despues);
        assertNotEquals("CON_NSO", despues.get("status").asText(), "sin aceptar CO no queda CON_NSO: " + despues);
        JsonNode eventos = ok(get("/api/admin/nso/events").param("type", "SETTINGS"));
        assertEquals(eventosAntes + 1, eventos.size());
        assertTrue(eventos.get(0).get("detail").asText().contains("solo los de Perú"), eventos.get(0).toString());

        // Umbral de revision
        JsonNode umbral = ok(withJson(put("/api/admin/nso/settings"), "{\"reviewMinScore\":0.8}"));
        assertEquals(0.8, umbral.get("reviewMinScore").asDouble(), 1e-9);
        assertTrue(service.awaitRematchIdle(60_000));

        // Volver a lo de siempre: el perfume recupera su NSO de Colombia.
        JsonNode restaurado = ok(withJson(put("/api/admin/nso/settings"), "{\"acceptCanCodes\":true,\"reviewMinScore\":0.66}"));
        assertTrue(restaurado.get("acceptCanCodes").asBoolean());
        assertEquals(0.66, restaurado.get("reviewMinScore").asDouble(), 1e-9);
        assertTrue(service.awaitRematchIdle(60_000));
        JsonNode otraVez = null;
        for (JsonNode i : ok(get("/api/admin/nso/index"))) if (i.get("productId").asLong() == storm.getId()) otraVez = i;
        assertEquals("CON_NSO", otraVez.get("status").asText(), otraVez.toString());

        // Mismos valores: no cambia nada, no deja evento.
        int eventosFin = ok(get("/api/admin/nso/events").param("type", "SETTINGS")).size();
        ok(withJson(put("/api/admin/nso/settings"), "{\"acceptCanCodes\":true}"));
        assertEquals(eventosFin, ok(get("/api/admin/nso/events").param("type", "SETTINGS")).size());
        // La config general sigue sin dejar tocar nso_* (las opciones van por /api/admin/nso/**).
        assertEquals(400, call(withJson(put("/api/admin/config/nso_accept_can_codes"), "{\"value\":\"false\"}")).getStatus());
    }
}
