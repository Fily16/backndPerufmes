package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.dto.OrderRequest;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.example.backendbvaberiaperfumes.repository.NsoRecordRepository;
import org.example.backendbvaberiaperfumes.repository.ProductNsoRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.example.backendbvaberiaperfumes.service.nso.NsoBlockedException;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.example.backendbvaberiaperfumes.service.nso.NsoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * El gate NSO protege el catalogo publico y la compra, no solo la interfaz: con el gate activo el anonimo no ve
 * ni compra lo que no tiene NSO (aunque llame a la API directo), el admin ve todo, y el mensaje al cliente nunca
 * menciona "NSO". Los pedidos se crean por el servicio (el controlador mandaria correos reales).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsogateintegrationtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        "spring.mail.password=",
        "resend.api.key="
})
class NsoGateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductRepository productRepo;
    @Autowired ProductNsoRepository productNsoRepo;
    @Autowired NsoRecordRepository recordRepo;
    @Autowired NsoService nsoService;
    @Autowired NsoGate gate;
    @Autowired ConsolidadoService consolidadoService;
    @Autowired ConsolidadoRepository consolidadoRepo;

    private static final String CODE = "NSOC99901-25PE";

    private Product product(String name) {
        Product p = new Product();
        p.setSku("NSO-GATE-" + UUID.randomUUID());
        p.setBrand("Lattafa");
        p.setName(name);
        p.setAvailable(true);
        p.setArchived(false);
        p.setMl(100);
        p.setWeightG(600);
        p.setWholesalePricePen(100.0);
        p.setPriceUsd(20.0);
        return productRepo.save(p);
    }

    private void record(String code, boolean active) {
        if (recordRepo.existsById(code)) return;
        NsoRecord r = new NsoRecord(code, "LATTAFA", "lattafa", "Perfume de prueba");
        r.setActive(active);
        recordRepo.save(r);
    }

    private void conNso(Product p, String code) {
        ProductNso st = new ProductNso(p.getId(), ProductNso.STATUS_CON_NSO);
        st.setNsoCode(code);
        st.setMatchedBy(ProductNso.MATCHED_MANUAL);
        productNsoRepo.save(st);
        gate.invalidate();
    }

    private String token() throws Exception {
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@aromastudio.pe\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(login).get("token").asText();
    }

    private Set<Long> ids(String body) throws Exception {
        Set<Long> ids = new HashSet<>();
        for (JsonNode n : json.readTree(body)) ids.add(n.get("id").asLong());
        return ids;
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

    private OrderRequest pedido(Long productId, int qty) {
        OrderRequest r = new OrderRequest();
        r.setClientName("Cliente Gate");
        r.setClientPhone("988777666");
        r.setChannel("CONSOLIDADO");
        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(productId);
        it.setQuantity(qty);
        r.setItems(List.of(it));
        return r;
    }

    private OrderRequest.OrderItemRequest item(Long productId, int qty) {
        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(productId);
        it.setQuantity(qty);
        return it;
    }

    @Test
    void gateOcultaAlAnonimoLoQueNoTieneNsoYElAdminVeTodo() throws Exception {
        record(CODE, true);
        Product permitido = product("Perfume permitido");
        Product bloqueado = product("Perfume pendiente");
        conNso(permitido, CODE);

        // Gate apagado: todo igual que antes
        Set<Long> antes = ids(mvc.perform(get("/api/products")).andReturn().getResponse().getContentAsString());
        assertTrue(antes.contains(bloqueado.getId()));

        nsoService.setGate(true);
        try {
            Set<Long> publico = ids(mvc.perform(get("/api/products")).andReturn().getResponse().getContentAsString());
            assertTrue(publico.contains(permitido.getId()));
            assertFalse(publico.contains(bloqueado.getId()));
            assertEquals(200, mvc.perform(get("/api/products/" + permitido.getId())).andReturn().getResponse().getStatus());
            assertEquals(404, mvc.perform(get("/api/products/" + bloqueado.getId())).andReturn().getResponse().getStatus());

            String t = token();
            assertEquals(200, mvc.perform(get("/api/products/" + bloqueado.getId()).header("Authorization", "Bearer " + t))
                    .andReturn().getResponse().getStatus(), "el admin sigue viendo el detalle");
            Set<Long> admin = ids(mvc.perform(get("/api/admin/products").header("Authorization", "Bearer " + t))
                    .andReturn().getResponse().getContentAsString());
            assertTrue(admin.contains(bloqueado.getId()), "GET /api/admin/products no aplica el gate");
        } finally {
            nsoService.setGate(false);
        }
        assertEquals(200, mvc.perform(get("/api/products/" + bloqueado.getId())).andReturn().getResponse().getStatus());
    }

    @Test
    void conNsoNoBastaSiElCodigoNoExisteOEstaDesactivado() throws Exception {
        record(CODE, true);
        record("NSOC99903-25PE", false);
        Product sinRegistro = product("Codigo inexistente");
        Product inactivo = product("Codigo inactivo");
        conNso(sinRegistro, "NSOC99902-25PE");
        conNso(inactivo, "NSOC99903-25PE");
        nsoService.setGate(true);
        try {
            assertFalse(gate.isPurchasable(sinRegistro.getId()));
            assertFalse(gate.isPurchasable(inactivo.getId()));
            assertEquals(404, mvc.perform(get("/api/products/" + inactivo.getId())).andReturn().getResponse().getStatus());
            assertThrows(NsoBlockedException.class, () -> gate.assertPurchasable(inactivo));
        } finally {
            nsoService.setGate(false);
        }
    }

    @Test
    void pedidoConPerfumeBloqueadoFallaConMensajeNeutralYEditarSoloDejaMantenerOBajar() {
        record(CODE, true);
        consolidadoAbierto();
        Product ok = product("Pedido permitido");
        Product viejo = product("Pedido antiguo");
        Product nuevo = product("Pedido nuevo");
        conNso(ok, CODE);
        // Pedido hecho ANTES de encender el gate (se debe poder mantener)
        String code = consolidadoService.createOrder(pedido(viejo.getId(), 2)).getOrderCode();

        nsoService.setGate(true);
        try {
            NsoBlockedException ex = assertThrows(NsoBlockedException.class,
                    () -> consolidadoService.createOrder(pedido(nuevo.getId(), 1)));
            assertEquals(List.of(nuevo.getId()), ex.getUnavailableProductIds());
            assertEquals("«Lattafa Pedido nuevo» ya no está disponible. Retíralo de tu pedido para continuar.", ex.getMessage());
            assertFalse(ex.getMessage().toUpperCase().contains("NSO"));
            assertNotNull(consolidadoService.createOrder(pedido(ok.getId(), 1)).getOrderCode());

            // mantener y bajar: permitido
            consolidadoService.editOrderByClient(code, "988777666", List.of(item(viejo.getId(), 2)));
            consolidadoService.editOrderByClient(code, "988777666", List.of(item(viejo.getId(), 1)));
            // subir o agregar uno bloqueado: no
            assertThrows(NsoBlockedException.class, () ->
                    consolidadoService.editOrderByClient(code, "988777666", List.of(item(viejo.getId(), 3))));
            assertThrows(NsoBlockedException.class, () -> consolidadoService.editOrderByClient(code, "988777666",
                    List.of(item(viejo.getId(), 1), item(nuevo.getId(), 1))));
        } finally {
            nsoService.setGate(false);
        }
    }

    @Test
    void apiNsoExigeTokenYNoCambiaElGateConCuerpoVacio() throws Exception {
        record(CODE, true);
        int anon = mvc.perform(get("/api/admin/nso/summary")).andReturn().getResponse().getStatus();
        assertTrue(anon == 401 || anon == 403, "sin token: " + anon);
        int anonAdmin = mvc.perform(get("/api/admin/products")).andReturn().getResponse().getStatus();
        assertTrue(anonAdmin == 401 || anonAdmin == 403, "sin token: " + anonAdmin);

        String t = token();
        nsoService.setGate(true);
        try {
            int s = mvc.perform(put("/api/admin/nso/gate").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
            assertEquals(400, s);
            assertTrue(gate.isActive(), "un cuerpo vacio no apaga el filtro");
        } finally {
            nsoService.setGate(false);
        }
    }
}
