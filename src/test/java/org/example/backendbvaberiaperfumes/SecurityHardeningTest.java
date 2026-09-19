package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.dto.OrderRequest;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.example.backendbvaberiaperfumes.repository.OrderRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Fase 0 (seguridad): endpoints que quedaban publicos por el anyRequest().permitAll()
 * y el precio que el cliente podia mandar al editar su pedido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:securityhardeningtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        // Nunca mandar correos reales desde el test (secret-mail.properties local trae la clave SMTP)
        "spring.mail.password=",
        "resend.api.key="
})
class SecurityHardeningTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired ProductRepository productRepo;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired ConsolidadoService consolidadoService;
    @Autowired org.example.backendbvaberiaperfumes.repository.AdminRepository adminRepoForSecurity;
    @Autowired org.example.backendbvaberiaperfumes.service.DataSeederService dataSeeder;

    // ================= helpers =================

    private String token() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@aromastudio.pe\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("token").asText();
    }

    private int status(RequestBuilder rb) throws Exception {
        return mvc.perform(rb).andReturn().getResponse().getStatus();
    }

    private void assertBloqueado(RequestBuilder rb, String que) throws Exception {
        int s = status(rb);
        assertTrue(s == 401 || s == 403, que + " debe exigir token (fue " + s + ")");
    }

    private Product producto(String sku, String name, double wholesale) {
        return productRepo.findBySku(sku).orElseGet(() -> {
            Product p = new Product();
            p.setSku(sku);
            p.setBrand("Lattafa");
            p.setName(name);
            p.setMl(100);
            p.setWeightG(600);
            p.setAvailable(true);
            p.setWholesalePricePen(wholesale);
            p.setPriceUsd(20.0);
            return productRepo.save(p);
        });
    }

    /** Garantiza un consolidado ABIERTO con plazo vigente para los pedidos por encargo. */
    private void consolidadoAbierto() {
        Consolidado active = consolidadoService.getActiveOrNull();
        if (active != null && consolidadoService.isOpenForOrders(active)) return;
        Consolidado c = new Consolidado();
        c.setStatus("ABIERTO");
        c.setStartAt(Instant.now().minusSeconds(60));
        c.setEndsAt(Instant.now().plus(2, ChronoUnit.DAYS));
        consolidadoRepo.save(c);
    }

    // ================= 1. Pedidos =================

    @Test
    void listaYDetalleDePedidosExigenToken() throws Exception {
        assertBloqueado(get("/api/orders"), "GET /api/orders");
        assertBloqueado(get("/api/orders/1"), "GET /api/orders/1");

        String t = token();
        assertEquals(200, status(get("/api/orders").header("Authorization", "Bearer " + t)),
                "con token el admin sigue viendo los pedidos");
    }

    @Test
    void consultaPorCodigoYCrearPedidoSiguenPublicos() throws Exception {
        consolidadoAbierto();
        Product p = producto("SEC-TEST-CODE", "Codigo Publico", 100.0);

        // POST /api/orders sigue publico (el cliente compra sin cuenta). Se prueba con un cuerpo
        // invalido: responde la validacion (400), no la seguridad, y NO dispara el correo real.
        int s = status(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertNotEquals(401, s);
        assertNotEquals(403, s);

        // El pedido se crea por el servicio (el controlador enviaria el correo de aviso).
        OrderRequest r = new OrderRequest();
        r.setClientName("Cliente");
        r.setClientPhone("911222333");
        r.setChannel("CONSOLIDADO");
        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(p.getId());
        it.setQuantity(1);
        r.setItems(List.of(it));
        String code = consolidadoService.createOrder(r).getOrderCode();
        assertNotNull(code);

        assertEquals(200, status(get("/api/orders/code/" + code)), "el cliente consulta su pedido sin token");

        // Codigo inexistente: el controlador lanza (sin ControllerAdvice MockMvc propaga la excepcion).
        // Lo importante es que la seguridad NO lo corta con 401/403.
        try {
            int s2 = status(get("/api/orders/code/NOEXISTE"));
            assertNotEquals(401, s2);
            assertNotEquals(403, s2);
        } catch (Exception llegoAlControlador) {
            // paso la seguridad y llego al controlador: correcto
        }
    }

    // ================= 2. Productos =================

    @Test
    void crearProductoExigeTokenYElCatalogoSiguePublico() throws Exception {
        long antes = productRepo.count();
        assertBloqueado(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"HACK-1\",\"brand\":\"X\",\"name\":\"Y\",\"available\":true}"),
                "POST /api/products");
        assertEquals(antes, productRepo.count(), "un anonimo no debe poder crear productos");

        assertEquals(200, status(get("/api/products")), "el catalogo publico sigue abierto");
        assertEquals(200, status(get("/api/retail/stock")), "el stock publico sigue abierto");
    }

    @Test
    void pricingSeMovioAAdmin() throws Exception {
        Product p = producto("SEC-TEST-PRICING", "Pricing", 100.0);

        // El endpoint publico viejo ya no existe
        assertEquals(404, status(get("/api/products/" + p.getId() + "/pricing")));

        assertBloqueado(get("/api/admin/products/" + p.getId() + "/pricing"), "GET /api/admin/products/{id}/pricing");
        String t = token();
        String json = mvc.perform(get("/api/admin/products/" + p.getId() + "/pricing")
                        .header("Authorization", "Bearer " + t))
                .andReturn().getResponse().getContentAsString();
        JsonNode n = om.readTree(json);
        assertTrue(n.has("landedCostUsd"));
        assertTrue(n.has("offers"));
    }

    // ================= 3. Inventario y ventas de tienda =================

    @Test
    void inventarioYVentasDeTiendaExigenToken() throws Exception {
        Product p = producto("SEC-TEST-RETAIL", "Retail", 100.0);
        assertBloqueado(get("/api/retail/inventory"), "GET /api/retail/inventory");
        assertBloqueado(post("/api/retail/inventory").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + p.getId() + ",\"quantity\":5}"), "POST /api/retail/inventory");
        assertBloqueado(get("/api/retail/sales"), "GET /api/retail/sales");
        assertBloqueado(post("/api/retail/sales").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + p.getId() + ",\"quantity\":1,\"salePricePen\":10}"),
                "POST /api/retail/sales");

        String t = token();
        assertEquals(200, status(get("/api/retail/inventory").header("Authorization", "Bearer " + t)));
        assertEquals(200, status(get("/api/retail/sales").header("Authorization", "Bearer " + t)));

        // form-sale sigue publico (lo llama Apps Script con su API key): sin key -> 403 del
        // controlador, no de la seguridad; con cuerpo vacio responde el propio endpoint.
        String formSale = mvc.perform(post("/api/retail/form-sale").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"mala\"}"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(formSale.contains("Invalid API key"), "form-sale debe llegar al controlador: " + formSale);
    }

    // ================= 4. Compra de tienda: errores de negocio -> 400 =================

    @Test
    void compraDeTiendaSinConsolidadoActivoDa400ConMensaje() throws Exception {
        Product p = producto("SEC-TEST-STOCKPURCHASE", "Compra Tienda", 100.0);
        List<Consolidado> todos = consolidadoRepo.findAll();
        Map<Long, String> statusOriginal = new HashMap<>();
        for (Consolidado c : todos) {
            statusOriginal.put(c.getId(), c.getStatus());
            c.setStatus("ENTREGADO");
        }
        consolidadoRepo.saveAll(todos);
        try {
            String t = token();
            var res = mvc.perform(post("/api/admin/stock-purchase")
                            .header("Authorization", "Bearer " + t)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[{\"productId\":" + p.getId() + ",\"quantity\":1}]}"))
                    .andReturn().getResponse();
            assertEquals(400, res.getStatus());
            assertTrue(om.readTree(res.getContentAsString()).get("message").asText().contains("consolidado"));
        } finally {
            List<Consolidado> restaurar = consolidadoRepo.findAll();
            for (Consolidado c : restaurar) {
                if (statusOriginal.containsKey(c.getId())) c.setStatus(statusOriginal.get(c.getId()));
            }
            consolidadoRepo.saveAll(restaurar);
        }
    }

    // ================= 4b. Consolidado activo: publico SIN datos de clientes ni ganancias =================

    @Test
    void consolidadoActivoPublicoNoExponeClientesNiGanancias() throws Exception {
        consolidadoAbierto();
        Product p = producto("SEC-TEST-ACTIVE", "Activo Publico", 150.0);
        OrderRequest r = new OrderRequest();
        r.setClientName("Cliente Privado Uno");
        r.setClientPhone("987654321");
        r.setChannel("CONSOLIDADO");
        r.setShippingName("Destinatario Privado");
        r.setShippingDni("44556677");
        r.setShippingPhone("912345678");
        r.setShippingAddress("Av. Secreta 123");
        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(p.getId());
        it.setQuantity(1);
        r.setItems(List.of(it));
        consolidadoService.createOrder(r);

        // Anonimo (lo que hace el checkout y cualquier curl): 200 con el id, sin pedidos ni datos de clientes.
        var res = mvc.perform(get("/api/consolidados/active")).andReturn().getResponse();
        assertEquals(200, res.getStatus());
        String body = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode active = om.readTree(body);
        assertTrue(active.get("id").asLong() > 0, "el panel (admin-shell, plan de compra) usa el id");
        assertEquals("ABIERTO", active.get("status").asText());
        for (String leak : new String[]{"orders", "clientName", "clientPhone", "shippingDni", "shippingAddress",
                "shippingPhone", "Cliente Privado Uno", "987654321", "44556677", "Av. Secreta 123",
                "totalCostUsd", "totalInvestmentPen", "totalRevenuePen", "projectedProfitPen", "courierCostUsd",
                "priceUsd", "unitCostUsdSnapshot", "notes"}) {
            assertFalse(body.contains(leak), "GET /api/consolidados/active publico expone «" + leak + "»: " + body);
        }

        // El admin sigue viendo los pedidos con sus datos por la ruta autenticada.
        String t = token();
        var orders = mvc.perform(get("/api/consolidados/" + active.get("id").asLong() + "/orders")
                        .header("Authorization", "Bearer " + t))
                .andReturn().getResponse();
        assertEquals(200, orders.getStatus());
        assertTrue(orders.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("987654321"));
        assertBloqueado(get("/api/consolidados/" + active.get("id").asLong() + "/orders"), "pedidos del consolidado");
        // La lista admin de consolidados sigue funcionando (sin arrastrar los pedidos de cada uno).
        var all = mvc.perform(get("/api/consolidados").header("Authorization", "Bearer " + t)).andReturn().getResponse();
        assertEquals(200, all.getStatus());
        assertFalse(all.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("987654321"));
    }

    // ================= 4c. Consola H2 cerrada por defecto + aviso de claves de ejemplo =================

    @Test
    void consolaH2CerradaPorDefecto() throws Exception {
        int s = status(get("/h2-console"));
        assertTrue(s == 403 || s == 404, "la consola H2 no debe responder sin H2_CONSOLE=true (fue " + s + ")");
        int s2 = status(get("/h2-console/login.do"));
        assertTrue(s2 == 403 || s2 == 404, "consola H2: " + s2);
    }

    @Test
    void avisoDeClavesDeEjemploSoloFueraDeH2() {
        String pg = "jdbc:postgresql://aiven.example:5432/defaultdb";
        String def = org.example.backendbvaberiaperfumes.config.DefaultSecretsWarning.DEFAULT_JWT_SECRET;
        assertTrue(org.example.backendbvaberiaperfumes.config.DefaultSecretsWarning
                .warnings("jdbc:h2:file:./data/aromastudio", def, true).isEmpty(), "local/tests: sin avisos");
        List<String> w = org.example.backendbvaberiaperfumes.config.DefaultSecretsWarning.warnings(pg, def, true);
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.get(0).contains("JWT_SECRET"));
        assertTrue(org.example.backendbvaberiaperfumes.config.DefaultSecretsWarning
                .warnings(pg, "un-secreto-propio-muy-largo-y-aleatorio-de-produccion-0123456789", false).isEmpty());
        assertEquals(1, org.example.backendbvaberiaperfumes.config.DefaultSecretsWarning.warnings(pg, "", false).size());
    }

    @Test
    void secretoJwtDeEjemploEnProduccionNoPermiteFalsificarTokens() {
        String pg = "jdbc:postgresql://aiven.example:5432/defaultdb";
        String def = org.example.backendbvaberiaperfumes.config.DefaultSecretsWarning.DEFAULT_JWT_SECRET;
        // Un atacante firma con el secreto de ejemplo que esta en el repo publico...
        String forged = new org.example.backendbvaberiaperfumes.config.JwtUtil(def, 3600000, "jdbc:h2:mem:x")
                .generateToken("admin@aromastudio.pe");
        // ...pero produccion sin JWT_SECRET usa una clave aleatoria de su arranque: el token falso no vale.
        org.example.backendbvaberiaperfumes.config.JwtUtil prod =
                new org.example.backendbvaberiaperfumes.config.JwtUtil(def, 3600000, pg);
        assertTrue(prod.isEphemeralSecret());
        assertFalse(prod.validateToken(forged), "un token firmado con el secreto publico no debe valer en produccion");
        assertTrue(prod.validateToken(prod.generateToken("admin@aromastudio.pe")), "sus propios tokens si valen");
        // Con un secreto propio, o en local con H2, se usa el configurado (las sesiones sobreviven reinicios).
        assertFalse(new org.example.backendbvaberiaperfumes.config.JwtUtil(
                "un-secreto-propio-muy-largo-y-aleatorio-de-produccion-0123456789", 3600000, pg).isEphemeralSecret());
        assertFalse(new org.example.backendbvaberiaperfumes.config.JwtUtil(def, 3600000, "jdbc:h2:mem:x").isEphemeralSecret());
    }

    @Test
    void cuentaLegacySocioConClaveFijaSeEliminaAlArrancar() throws Exception {
        // Una BD de produccion creada por versiones antiguas trae la cuenta con la clave fija del repo publico.
        adminRepoForSecurity.save(new org.example.backendbvaberiaperfumes.model.Admin("socio@aromastudio.pe",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("socio123"), "Socio"));
        assertTrue(adminRepoForSecurity.findByEmail("socio@aromastudio.pe").isPresent());
        dataSeeder.run(); // lo mismo que pasa en cada arranque
        assertTrue(adminRepoForSecurity.findByEmail("socio@aromastudio.pe").isEmpty(),
                "la cuenta con clave fija en el codigo publico debe eliminarse");
        assertTrue(adminRepoForSecurity.findByEmail("admin@aromastudio.pe").isPresent(), "el admin principal sigue");
        int s = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"socio@aromastudio.pe\",\"password\":\"socio123\"}")).andReturn().getResponse().getStatus();
        assertNotEquals(200, s, "ya no se puede entrar con socio123");
    }

    // ================= 5. Editar pedido: el precio lo decide el backend =================

    @Test
    void editarPedidoIgnoraElPrecioQueMandaElCliente() throws Exception {
        consolidadoAbierto();
        Product viejo = producto("SEC-TEST-EDIT-A", "Edit Ya Pedido", 120.0);
        Product nuevo = producto("SEC-TEST-EDIT-B", "Edit Agregado", 200.0);

        OrderRequest r = new OrderRequest();
        r.setClientName("Cliente Edit");
        r.setClientPhone("944555666");
        r.setChannel("CONSOLIDADO");
        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(viejo.getId());
        it.setQuantity(1);
        r.setItems(List.of(it));
        Order order = consolidadoService.createOrder(r);
        String code = order.getOrderCode();

        // El precio del catalogo sube despues del pedido: la linea ya pedida conserva su precio.
        viejo.setWholesalePricePen(130.0);
        productRepo.save(viejo);

        // El cliente intenta pagar S/1 por todo (endpoint publico, sin token).
        String body = "{\"clientName\":\"Cliente Edit\",\"clientPhone\":\"944555666\",\"existingOrderCode\":\"" + code + "\","
                + "\"items\":[{\"productId\":" + viejo.getId() + ",\"quantity\":2,\"unitPricePen\":1.0},"
                + "{\"productId\":" + nuevo.getId() + ",\"quantity\":1,\"unitPricePen\":1.0}]}";
        var res = mvc.perform(put("/api/orders/edit-by-client").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertEquals(200, res.getStatus(), res.getContentAsString());

        JsonNode edited = om.readTree(res.getContentAsString());
        double precioViejo = -1, precioNuevo = -1;
        for (JsonNode item : edited.get("items")) {
            long pid = item.get("product").get("id").asLong();
            if (pid == viejo.getId()) {
                precioViejo = item.get("unitPricePen").asDouble();
                assertEquals(2, item.get("quantity").asInt());
            }
            if (pid == nuevo.getId()) precioNuevo = item.get("unitPricePen").asDouble();
        }
        assertEquals(120.0, precioViejo, 0.001, "el item que ya estaba conserva su precio guardado");
        assertEquals(200.0, precioNuevo, 0.001, "el item nuevo usa el precio del backend");
        assertEquals(440.0, edited.get("totalPen").asDouble(), 0.001);

        // Y en la BD (no solo en la respuesta)
        Order enBd = orderRepo.findByOrderCode(code).orElseThrow();
        assertEquals(440.0, enBd.getTotalPen(), 0.001);
    }
}
