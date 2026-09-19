package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.dto.OrderRequest;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.NsoEvent;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.example.backendbvaberiaperfumes.repository.NsoEventRepository;
import org.example.backendbvaberiaperfumes.repository.NsoRecordRepository;
import org.example.backendbvaberiaperfumes.repository.ProductNsoRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.example.backendbvaberiaperfumes.service.ProductMergeService;
import org.example.backendbvaberiaperfumes.service.RecommendationService;
import org.example.backendbvaberiaperfumes.service.RetailService;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.example.backendbvaberiaperfumes.service.nso.NsoService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Cableado del filtro NSO (parte 1) de punta a punta por HTTP: pedidos, edicion de pedidos, compra de tienda,
 * lanzar a stock, stock publico, promociones, recomendaciones, config, panel y los ganchos de producto
 * (fusion, borrado, cambio de nombre). Con el gate APAGADO todo se comporta como antes; con el gate ACTIVO lo que
 * no tiene NSO no se muestra ni se compra, y los errores listan TODOS los perfumes bloqueados juntos.
 *
 * Catalogo sintetico de una marca inventada (QUORVANT). Sin @Transactional (commits reales: los ganchos corren
 * tras el commit). Los pedidos por HTTP no mandan correos: credenciales de correo vacias en las propiedades.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsowiringorderspromostest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        // Nunca mandar correos reales desde el test (secret-mail.properties local trae la clave SMTP)
        "spring.mail.password=",
        "resend.api.key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NsoWiringOrdersPromosTest {

    static final String CSV = String.join("\n",
            "nso,categoria,marca,producto,ean,titular,ruc",
            "NSOC91001-25PE,Arabe,QUORVANT,AGUA DE PERFUME-LUNAR ORCHARD,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC91002-25PE,Arabe,QUORVANT,AGUA DE PERFUME-CRYSTAL HARBOR,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC91003-25PE,Arabe,QUORVANT,AGUA DE PERFUME-SILENT MEADOW,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC91004-25PE,Arabe,QUORVANT,AGUA DE PERFUME-VELVET DUNE,,IMPORTADORA DEMO SAC,20123456789") + "\n";

    static final String PHONE = "955444333";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NsoService nsoService;
    @Autowired NsoGate gate;
    @Autowired ProductRepository productRepo;
    @Autowired ProductNsoRepository productNsoRepo;
    @Autowired NsoRecordRepository recordRepo;
    @Autowired NsoEventRepository eventRepo;
    @Autowired AppConfigRepository configRepo;
    @Autowired ConsolidadoService consolidadoService;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired RetailService retailService;
    @Autowired RecommendationService recommendationService;
    @Autowired ProductMergeService mergeService;

    // ============================ helpers ============================

    private Product product(String brand, String name) {
        Product p = new Product();
        p.setSku("NSO-WIRE-" + UUID.randomUUID());
        p.setBrand(brand);
        p.setName(name);
        p.setMl(100);
        p.setWeightG(600);
        p.setAvailable(true);
        p.setArchived(false);
        p.setWholesalePricePen(150.0);
        p.setPriceUsd(20.0);
        p.setImageUrl("https://example.com/" + UUID.randomUUID() + ".jpg");
        return productRepo.save(p);
    }

    /** Perfume con NSO asignado a mano (bloqueado, deterministico). */
    private Product conNso(String name, String code) {
        Product p = product("Quorvant", name);
        nsoService.assignManual(p.getId(), new NsoService.ManualCode(code));
        gate.invalidate();
        return p;
    }

    /** Perfume sin NSO: marca que no esta en la lista. */
    private Product sinNso(String name) {
        Product p = product("Zentharo", name);
        nsoService.rematchProducts(List.of(p.getId()));
        gate.invalidate();
        return p;
    }

    private void ensureCatalog() {
        if (recordRepo.count() > 0) return;
        nsoService.uploadCatalog("nso.csv", CSV.getBytes(StandardCharsets.UTF_8));
        assertTrue(nsoService.awaitRematchIdle(60_000), "el rematch en segundo plano debe terminar");
    }

    private ProductNso state(Product p) {
        return productNsoRepo.findById(p.getId()).orElse(null);
    }

    private String status(Product p) {
        ProductNso st = state(p);
        return st == null ? ProductNso.STATUS_SIN_VERIFICAR : st.getStatus();
    }

    private String token() throws Exception {
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@aromastudio.pe\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(login).get("token").asText();
    }

    private MockHttpServletResponse call(RequestBuilder rb) throws Exception {
        MockHttpServletResponse res = mvc.perform(rb).andReturn().getResponse();
        res.setCharacterEncoding("UTF-8");
        return res;
    }

    private JsonNode body(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }

    private String toJson(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    private Set<Long> longs(JsonNode array) {
        Set<Long> out = new HashSet<>();
        for (JsonNode n : array) out.add(n.asLong());
        return out;
    }

    private Set<Long> ids(JsonNode array) {
        Set<Long> out = new HashSet<>();
        for (JsonNode n : array) out.add(n.get("id").asLong());
        return out;
    }

    private void consolidadoAbierto() {
        Consolidado active = consolidadoService.getActiveOrNull();
        if (active != null && consolidadoService.isOpenForOrders(active)) return;
        Consolidado c = new Consolidado();
        c.setStatus("ABIERTO");
        c.setStartAt(Instant.now().minusSeconds(60));
        c.setEndsAt(Instant.now().plus(2, ChronoUnit.DAYS));
        consolidadoRepo.save(c);
    }

    private Map<String, Object> item(Long productId, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        return m;
    }

    private Map<String, Object> pedido(List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientName", "Cliente Wiring");
        m.put("clientPhone", PHONE);
        m.put("channel", "CONSOLIDADO");
        m.put("items", items);
        return m;
    }

    private Map<String, Object> promo(String name, Long... productIds) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : productIds) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("productId", id);
            items.add(it);
        }
        Map<String, Object> exclusivo = new LinkedHashMap<>();
        exclusivo.put("name", "Perfume exclusivo de la promo");
        items.add(exclusivo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pricePen", 300.0);
        m.put("profitPen", 50.0);
        m.put("stockQty", 5);
        m.put("active", true);
        m.put("items", items);
        return m;
    }

    private String config(String key) {
        return configRepo.findByConfigKey(key).map(AppConfig::getConfigValue).orElse(null);
    }

    // ============================ 1. gate apagado: todo igual ============================

    @Test
    @Order(1)
    void conGateApagadoTodoSeComportaComoAntes() throws Exception {
        ensureCatalog();
        consolidadoAbierto();
        assertFalse(gate.isActive());
        String t = token();
        Product ok = conNso("Lunar Orchard 3.4 Oz Edp Women", "NSOC91001-25PE");
        Product a = sinNso("Apagado Uno");
        Product b = sinNso("Apagado Dos");
        assertEquals(ProductNso.STATUS_SIN_NSO, status(a));

        // Pedido publico con perfumes sin NSO: se acepta
        MockHttpServletResponse order = call(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(pedido(List.of(item(a.getId(), 1), item(b.getId(), 1))))));
        assertEquals(200, order.getStatus(), order.getContentAsString());

        // Compra de tienda (preview) y lanzar a stock: sin bloqueos
        MockHttpServletResponse preview = call(post("/api/admin/stock-purchase/preview").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":" + a.getId() + ",\"quantity\":1}]}"));
        assertEquals(200, preview.getStatus(), preview.getContentAsString());
        MockHttpServletResponse launch = call(post("/api/admin/retail/launch").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(List.of(item(b.getId(), 2)))));
        assertEquals(200, launch.getStatus());
        assertEquals(1, body(launch).get("launched").asInt());
        assertEquals(0, body(launch).get("blocked").size());

        // Stock publico completo
        JsonNode stock = body(call(get("/api/retail/stock")));
        assertTrue(stock.has(String.valueOf(b.getId())));

        // Promo con perfume sin NSO: se crea y se muestra
        MockHttpServletResponse created = call(post("/api/admin/promotions").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo apagado", ok.getId(), a.getId()))));
        assertEquals(200, created.getStatus(), created.getContentAsString());
        long promoId = body(created).get("id").asLong();
        assertTrue(ids(body(call(get("/api/promotions/active")))).contains(promoId));
        assertEquals(200, call(get("/api/promotions/" + promoId)).getStatus());

        // Recomendaciones: el pool trae tambien los sin NSO
        List<Long> rec = recommendationService.relatedFor(ok.getId(), 500).stream().map(Product::getId).toList();
        assertTrue(rec.contains(a.getId()));

        // Panel: gate no efectivo
        JsonNode dash = body(call(get("/api/admin/dashboard").header("Authorization", "Bearer " + t)));
        assertFalse(dash.get("nsoGateEffective").asBoolean());
        assertTrue(dash.get("nsoSin").asInt() >= 2);
        assertTrue(dash.has("nsoConNso") && dash.has("nsoPending") && dash.has("nsoMarca"));

        // Summary expone acceptCanCodes
        assertEquals(true, nsoService.summary().get("acceptCanCodes"));
    }

    // ============================ 2. pedidos y edicion ============================

    @Test
    @Order(2)
    void pedidoConVariosBloqueadosDevuelveTodosLosIdsYEditarSoloDejaMantenerOBajar() throws Exception {
        ensureCatalog();
        consolidadoAbierto();
        Product ok = conNso("Crystal Harbor 3.4 Oz Edp Women", "NSOC91002-25PE");
        Product x = sinNso("Pedido Bloqueado Uno");
        Product y = sinNso("Pedido Bloqueado Dos");
        // Pedido hecho ANTES de encender el filtro
        OrderRequest r = new OrderRequest();
        r.setClientName("Cliente Wiring");
        r.setClientPhone(PHONE);
        r.setChannel("CONSOLIDADO");
        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(x.getId());
        it.setQuantity(2);
        r.setItems(List.of(it));
        String code = consolidadoService.createOrder(r).getOrderCode();

        nsoService.setGate(true);
        try {
            MockHttpServletResponse res = call(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(pedido(List.of(item(ok.getId(), 1), item(x.getId(), 1), item(y.getId(), 1))))));
            assertEquals(400, res.getStatus(), res.getContentAsString());
            JsonNode err = body(res);
            assertEquals(Set.of(x.getId(), y.getId()), longs(err.get("unavailableProductIds")));
            String msg = err.get("message").asText();
            assertTrue(msg.contains("Pedido Bloqueado Uno") && msg.contains("Pedido Bloqueado Dos"), msg);
            assertTrue(msg.contains("ya no están disponibles"), msg);
            assertFalse(msg.toUpperCase().contains("NSO"), "el cliente nunca ve la palabra NSO: " + msg);

            // Solo perfumes con NSO: pasa
            assertEquals(200, call(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(pedido(List.of(item(ok.getId(), 1)))))).getStatus());

            // Edicion: mantener (2) y bajar (1) se puede
            Map<String, Object> edit = pedido(List.of(item(x.getId(), 2)));
            edit.put("existingOrderCode", code);
            MockHttpServletResponse keep = call(put("/api/orders/edit-by-client").contentType(MediaType.APPLICATION_JSON).content(toJson(edit)));
            assertEquals(200, keep.getStatus(), keep.getContentAsString());
            edit.put("items", List.of(item(x.getId(), 1)));
            assertEquals(200, call(put("/api/orders/edit-by-client").contentType(MediaType.APPLICATION_JSON).content(toJson(edit))).getStatus());

            // Subir el bloqueado y agregar otro bloqueado: 400 con los dos juntos
            edit.put("items", List.of(item(x.getId(), 3), item(y.getId(), 1), item(ok.getId(), 1)));
            MockHttpServletResponse up = call(put("/api/orders/edit-by-client").contentType(MediaType.APPLICATION_JSON).content(toJson(edit)));
            assertEquals(400, up.getStatus(), up.getContentAsString());
            assertEquals(Set.of(x.getId(), y.getId()), longs(body(up).get("unavailableProductIds")));
            JsonNode stored = body(call(get("/api/orders/code/" + code)));
            assertEquals(1, stored.get("items").size());
            assertEquals(1, stored.get("items").get(0).get("quantity").asInt(), "no se toco el pedido");
        } finally {
            nsoService.setGate(false);
        }
    }

    // ============================ 3. compra de tienda, lanzar a stock, stock publico ============================

    @Test
    @Order(3)
    void compraDeTiendaLanzarAStockYStockPublicoRespetanElFiltro() throws Exception {
        ensureCatalog();
        consolidadoAbierto();
        String t = token();
        Product ok = conNso("Silent Meadow 3.4 Oz Edp Women", "NSOC91003-25PE");
        Product x = sinNso("Tienda Bloqueado Uno");
        Product y = sinNso("Tienda Bloqueado Dos");
        retailService.addStock(y.getId(), 4, 100.0, "stock previo");
        retailService.addStock(ok.getId(), 3, 100.0, "stock previo");

        nsoService.setGate(true);
        try {
            String items = "{\"items\":[{\"productId\":" + ok.getId() + ",\"quantity\":1},{\"productId\":" + x.getId()
                    + ",\"quantity\":1},{\"productId\":" + y.getId() + ",\"quantity\":2}]}";
            for (String url : List.of("/api/admin/stock-purchase", "/api/admin/stock-purchase/preview")) {
                MockHttpServletResponse res = call(post(url).header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(items));
                assertEquals(400, res.getStatus(), url + " " + res.getContentAsString());
                assertEquals(Set.of(x.getId(), y.getId()), longs(body(res).get("unavailableProductIds")));
                String msg = body(res).get("message").asText();
                assertTrue(msg.contains("Tienda Bloqueado Uno") && msg.contains("NSO"), msg);
            }

            MockHttpServletResponse launch = call(post("/api/admin/retail/launch").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(List.of(item(ok.getId(), 1), item(x.getId(), 1)))));
            assertEquals(200, launch.getStatus());
            JsonNode lb = body(launch);
            assertEquals(1, lb.get("launched").asInt());
            assertEquals(1, lb.get("blocked").size());
            assertEquals(x.getId(), lb.get("blocked").get(0).get("productId").asLong());
            assertEquals("Zentharo Tienda Bloqueado Uno", lb.get("blocked").get(0).get("name").asText());

            // Stock publico: sin los ocultos; con token (pantallas admin) completo
            JsonNode anon = body(call(get("/api/retail/stock")));
            assertTrue(anon.has(String.valueOf(ok.getId())));
            assertFalse(anon.has(String.valueOf(y.getId())));
            JsonNode admin = body(call(get("/api/retail/stock").header("Authorization", "Bearer " + t)));
            assertTrue(admin.has(String.valueOf(y.getId())));
        } finally {
            nsoService.setGate(false);
        }
        assertTrue(body(call(get("/api/retail/stock"))).has(String.valueOf(y.getId())), "gate apagado: stock completo");
    }

    // ============================ 4. promociones ============================

    @Test
    @Order(4)
    void promoConPerfumeSinNsoSeOcultaYNoSePuedeCrearNiEditar() throws Exception {
        ensureCatalog();
        consolidadoAbierto();
        String t = token();
        Product ok = conNso("Lunar Orchard 1.7 Oz Edp Women", "NSOC91001-25PE");
        Product x = sinNso("Promo Bloqueado");
        long mala = body(call(post("/api/admin/promotions").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo mala", ok.getId(), x.getId()))))).get("id").asLong();
        long buena = body(call(post("/api/admin/promotions").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo buena", ok.getId()))))).get("id").asLong();

        nsoService.setGate(true);
        try {
            Set<Long> activas = ids(body(call(get("/api/promotions/active"))));
            assertFalse(activas.contains(mala), "promo con perfume sin NSO no se muestra");
            assertTrue(activas.contains(buena), "los perfumes exclusivos de la promo no se validan");
            assertEquals(404, call(get("/api/promotions/" + mala)).getStatus());
            assertEquals(200, call(get("/api/promotions/" + buena)).getStatus());
            assertEquals(200, call(get("/api/promotions/" + mala).header("Authorization", "Bearer " + t)).getStatus(),
                    "el admin la sigue viendo");

            MockHttpServletResponse edit = call(put("/api/admin/promotions/" + mala).header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo mala", ok.getId(), x.getId()))));
            assertEquals(400, edit.getStatus(), edit.getContentAsString());
            assertTrue(body(edit).get("message").asText().contains("Zentharo Promo Bloqueado"), edit.getContentAsString());
            MockHttpServletResponse create = call(post("/api/admin/promotions").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo nueva", x.getId()))));
            assertEquals(400, create.getStatus(), create.getContentAsString());
            assertEquals(200, call(put("/api/admin/promotions/" + buena).header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo buena", ok.getId())))).getStatus());

            // Comprar la promo con un perfume sin NSO por la API directa: 400 con su id
            Map<String, Object> order = pedido(List.of());
            order.put("promotions", List.of(Map.of("promotionId", mala, "quantity", 1)));
            MockHttpServletResponse res = call(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(toJson(order)));
            assertEquals(400, res.getStatus(), res.getContentAsString());
            assertEquals(Set.of(x.getId()), longs(body(res).get("unavailableProductIds")));

            // El boton «Ocultar» del panel reenvia la promo COMPLETA (con el perfume bloqueado) y active=false:
            // ocultarla siempre se puede.
            Map<String, Object> ocultar = promo("Promo mala", ok.getId(), x.getId());
            ocultar.put("active", false);
            MockHttpServletResponse hidden = call(put("/api/admin/promotions/" + mala).header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(ocultar)));
            assertEquals(200, hidden.getStatus(), hidden.getContentAsString());
            assertFalse(body(hidden).get("active").asBoolean());
            // Volver a activarla con el perfume bloqueado: no.
            MockHttpServletResponse reactivar = call(put("/api/admin/promotions/" + mala).header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo mala", ok.getId(), x.getId()))));
            assertEquals(400, reactivar.getStatus(), reactivar.getContentAsString());
            assertTrue(body(reactivar).get("message").asText().contains("oculta la promoción"), reactivar.getContentAsString());
            // Editarla para QUITAR el perfume bloqueado (y dejarla activa): si.
            MockHttpServletResponse sinBloqueado = call(put("/api/admin/promotions/" + mala).header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(promo("Promo mala", ok.getId()))));
            assertEquals(200, sinBloqueado.getStatus(), sinBloqueado.getContentAsString());
            assertTrue(ids(body(call(get("/api/promotions/active")))).contains(mala), "ya sin el perfume bloqueado se muestra");
        } finally {
            nsoService.setGate(false);
        }
        assertTrue(ids(body(call(get("/api/promotions/active")))).contains(mala), "gate apagado: vuelve a mostrarse");

        // Ganancia interna: nunca en las rutas publicas, si en las del panel.
        for (JsonNode p : body(call(get("/api/promotions/active")))) assertFalse(p.has("profitPen"), p.toString());
        JsonNode detalle = body(call(get("/api/promotions/" + buena)));
        assertFalse(detalle.has("profitPen"), detalle.toString());
        assertTrue(detalle.has("pricePen") && detalle.has("items") && detalle.has("stockQty"), detalle.toString());
        boolean adminVe = false;
        for (JsonNode p : body(call(get("/api/admin/promotions").header("Authorization", "Bearer " + t)))) {
            if (p.get("id").asLong() == buena) adminVe = p.has("profitPen") && p.get("profitPen").asDouble() == 50.0;
        }
        assertTrue(adminVe, "el panel sigue viendo la ganancia");
    }

    // ============================ 5. recomendaciones, config, panel ============================

    @Test
    @Order(5)
    void recomendacionesConfigYPanel() throws Exception {
        ensureCatalog();
        String t = token();
        Product base = conNso("Crystal Harbor 1.7 Oz Edp Women", "NSOC91002-25PE");
        Product otro = conNso("Silent Meadow 1.7 Oz Edp Women", "NSOC91003-25PE");
        // Se parece MAS a la base (misma marca + mismo nombre) pero no tiene NSO confirmado: queda por revisar/marca.
        Product parecido = product("Quorvant", "Crystal Harbor Intense Night");
        nsoService.rematchProducts(List.of(parecido.getId()));
        assertNotEquals(ProductNso.STATUS_CON_NSO, status(parecido));

        nsoService.setGate(true);
        try {
            List<Long> rec = recommendationService.relatedFor(base.getId(), 500).stream().map(Product::getId).toList();
            assertFalse(rec.contains(parecido.getId()));
            assertTrue(rec.contains(otro.getId()));
            for (Long id : rec) assertTrue(gate.isPublicId(id), "recomendado oculto: " + id);
            // Con limite 1 igual devuelve 1 (se filtra el pool, no se recorta el resultado)
            assertEquals(1, recommendationService.relatedFor(base.getId(), 1).size());

            // Las opciones nso_* no se cambian por la config generica
            MockHttpServletResponse cfg = call(put("/api/admin/config/nso_gate_enabled").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"false\"}"));
            assertEquals(400, cfg.getStatus());
            assertEquals("Esta opción se cambia desde la pantalla NSO.", body(cfg).get("message").asText());
            assertEquals("true", config("nso_gate_enabled"));
            assertEquals(400, call(put("/api/admin/config/NSO_ACCEPT_CAN_CODES").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"false\"}")).getStatus());
            assertEquals(200, call(put("/api/admin/config/wiring_test_key").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"x\"}")).getStatus());

            JsonNode dash = body(call(get("/api/admin/dashboard").header("Authorization", "Bearer " + t)));
            assertTrue(dash.get("nsoGateEffective").asBoolean());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Object[] row : productNsoRepo.countCurrentProductsByStatus()) counts.put((String) row[0], ((Number) row[1]).longValue());
            assertEquals(counts.getOrDefault(ProductNso.STATUS_CON_NSO, 0L).intValue(), dash.get("nsoConNso").asInt());
            assertEquals(counts.getOrDefault(ProductNso.STATUS_SIN_NSO, 0L).intValue(), dash.get("nsoSin").asInt());
            assertEquals(counts.getOrDefault(ProductNso.STATUS_MARCA_CON_NSO, 0L).intValue(), dash.get("nsoMarca").asInt());
            assertEquals(nsoService.pendingCount(), dash.get("nsoPending").asLong());
            assertTrue(dash.get("nsoConNso").asInt() >= 2);
        } finally {
            nsoService.setGate(false);
        }

        // Alias de marca: catalogBrandKey acepta el nombre tal cual (no solo la clave plegada)
        for (String target : List.of("QUORVANT", "Quorvant Parfums")) {
            MockHttpServletResponse al = call(post("/api/admin/nso/brand-aliases").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("supplierBrand", "Quorvant Paris", "catalogBrandKey", target))));
            assertEquals(200, al.getStatus(), target + " " + al.getContentAsString());
        }
    }

    // ============================ 6. ganchos de producto ============================

    @Test
    @Order(6)
    void fusionBorradoYCambioDeNombreActualizanElNso() throws Exception {
        ensureCatalog();
        String t = token();

        // Fusion: el NSO del duplicado pasa al canonico (tras el commit de la fusion)
        Product canonico = product("Quorvant", "Fusion Canonico Edp");
        nsoService.rematchProducts(List.of(canonico.getId()));
        assertNotEquals(ProductNso.STATUS_CON_NSO, status(canonico));
        Product duplicado = conNso("Fusion Duplicado Edp", "NSOC91002-25PE");
        mergeService.merge(canonico.getId(), duplicado.getId());
        ProductNso st = state(canonico);
        assertNotNull(st, "el canonico tiene estado NSO tras la fusion");
        assertEquals(ProductNso.STATUS_CON_NSO, st.getStatus());
        assertEquals("NSOC91002-25PE", st.getNsoCode());
        assertTrue(st.getLocked());
        assertNull(state(duplicado));
        assertEquals(1, eventRepo.recent(canonico.getId(), NsoEvent.TYPE_PRODUCT_MERGED, 10).size());

        // Borrado: limpia product_nso
        Product borrar = conNso("Borrar Edp Women", "NSOC91001-25PE");
        assertNotNull(state(borrar));
        assertEquals(204, call(delete("/api/products/" + borrar.getId()).header("Authorization", "Bearer " + t)).getStatus());
        assertFalse(productRepo.existsById(borrar.getId()));
        assertNull(state(borrar), "el borrado limpia product_nso");
        assertEquals(1, eventRepo.recent(borrar.getId(), NsoEvent.TYPE_PRODUCT_DELETED, 10).size());

        // Cambio de nombre: se re-verifica al instante (nombre sin hermanos de otro genero en el catalogo)
        Product renombrar = product("Quorvant", "Nombre Provisional Raro");
        nsoService.rematchProducts(List.of(renombrar.getId()));
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, status(renombrar));
        MockHttpServletResponse upd = call(put("/api/products/" + renombrar.getId()).header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Velvet Dune 3.4 Oz Edp Women\"}"));
        assertEquals(200, upd.getStatus(), upd.getContentAsString());
        assertEquals(ProductNso.STATUS_CON_NSO, status(renombrar));
        assertEquals("NSOC91004-25PE", state(renombrar).getNsoCode());

        // Producto nuevo por la API: queda verificado
        MockHttpServletResponse created = call(post("/api/products").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"NSO-WIRE-NEW-" + UUID.randomUUID() + "\",\"brand\":\"Zentharo\",\"name\":\"Nuevo Sin Nso\",\"available\":true}"));
        assertEquals(200, created.getStatus(), created.getContentAsString());
        assertEquals(ProductNso.STATUS_SIN_NSO,
                productNsoRepo.findById(body(created).get("id").asLong()).map(ProductNso::getStatus).orElse(null));

        // Codigo de barras a mano: re-verifica el perfume (antes no tenia fila NSO)
        Product gtin = product("Zentharo", "Con Codigo");
        assertNull(state(gtin));
        assertEquals(200, call(put("/api/admin/products/" + gtin.getId() + "/gtin").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("{\"gtin\":\"2000000000015\"}")).getStatus());
        assertEquals(ProductNso.STATUS_SIN_NSO, status(gtin));
    }
}
