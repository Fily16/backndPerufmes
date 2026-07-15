package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.ConsolidadoPublicDTO;
import org.example.backendbvaberiaperfumes.dto.OrderRequest;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.MediaImage;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.example.backendbvaberiaperfumes.repository.MediaImageRepository;
import org.example.backendbvaberiaperfumes.repository.OrderRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.service.ConsolidadoScheduler;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.example.backendbvaberiaperfumes.service.RetailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ciclo de vida del consolidado con fechas: cierre automatico, candado de
 * pedidos por encargo y la garantia de que las ventas de STOCK nunca dependen
 * del plazo. Casos escritos contra el comportamiento real de produccion.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:lifecycletest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class ConsolidadoLifecycleTest {

    @Autowired ConsolidadoService service;
    @Autowired ConsolidadoScheduler scheduler;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired ProductRepository productRepo;
    @Autowired MediaImageRepository mediaRepo;
    @Autowired RetailService retailService;

    private Product perfume;

    @BeforeEach
    void limpiar() {
        // El seeder crea un ABIERTO sin fechas: cada test arma su propio escenario.
        orderRepo.deleteAll();
        consolidadoRepo.deleteAll();
        // JUnit crea una instancia nueva por test: el producto se busca, no se re-crea.
        perfume = productRepo.findBySku("LIFE-TEST-1").orElseGet(() -> {
            Product p = new Product();
            p.setSku("LIFE-TEST-1");
            p.setBrand("Lattafa");
            p.setName("Khamrah Test");
            p.setMl(100);
            p.setWeightG(600);
            p.setAvailable(true);
            p.setWholesalePricePen(120.0);
            p.setStockPricePen(150.0);
            p.setPriceUsd(20.0);
            Product saved = productRepo.save(p);
            retailService.addStock(saved.getId(), 500, 80.0, "stock para tests");
            return saved;
        });
    }

    private Consolidado consolidado(String status, Instant startAt, Instant endsAt) {
        Consolidado c = new Consolidado();
        c.setStatus(status);
        c.setStartAt(startAt);
        c.setEndsAt(endsAt);
        return consolidadoRepo.save(c);
    }

    private OrderRequest pedido(String channel, int qty) {
        OrderRequest r = new OrderRequest();
        r.setClientName("Cliente Test");
        r.setClientPhone("999888777");
        r.setChannel(channel);
        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(perfume.getId());
        it.setQuantity(qty);
        r.setItems(List.of(it));
        return r;
    }

    // ================= 1. Sin consolidado abierto =================

    @Test
    void sinAbiertoElEncargoSeRechazaPeroElStockSeVende() {
        consolidado("CERRADO", null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createOrder(pedido("CONSOLIDADO", 1)));
        assertEquals("El consolidado está cerrado. Espera la apertura del próximo.", ex.getMessage());

        // La venta inmediata NO depende del plazo: se ancla al consolidado mas nuevo.
        Order stock = service.createOrder(pedido("STOCK", 1));
        assertEquals("STOCK", stock.getChannel());
        assertNotNull(stock.getConsolidado(), "el pedido STOCK nunca puede quedar sin consolidado (se perderia en el admin)");
        assertEquals("CERRADO", stock.getConsolidado().getStatus());
    }

    // ================= 2. Plazo vencido con status aun ABIERTO =================

    @Test
    void plazoVencidoRechazaAunqueElStatusSigaAbierto() {
        // Escenario real: el scheduler todavia no corrio (cold start de Render).
        consolidado("ABIERTO", Instant.now().minus(2, ChronoUnit.DAYS), Instant.now().minusSeconds(30));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrder(pedido("CONSOLIDADO", 1)));

        ConsolidadoPublicDTO dto = service.getCurrentPublic();
        assertFalse(dto.open, "el DTO publico ya debe decir cerrado aunque el status siga ABIERTO");
    }

    @Test
    void plazoVigenteAceptaElEncargo() {
        consolidado("ABIERTO", Instant.now().minusSeconds(60), Instant.now().plus(3, ChronoUnit.DAYS));

        Order o = service.createOrder(pedido("CONSOLIDADO", 2));
        assertEquals("CONSOLIDADO", o.getChannel());
        assertTrue(service.getCurrentPublic().open);
    }

    // ================= 3. Agregar a un pedido existente =================

    @Test
    void agregarAPedidoStockFuncionaConConsolidadoCerrado() {
        Consolidado c = consolidado("ABIERTO", Instant.now().minusSeconds(60), Instant.now().plus(1, ChronoUnit.DAYS));
        Order stock = service.createOrder(pedido("STOCK", 1));
        String code = stock.getOrderCode();
        assertNotNull(code);

        // El consolidado cierra despues de que el cliente ya tenia su pedido.
        c.setStatus("CERRADO");
        consolidadoRepo.save(c);

        OrderRequest add = pedido("STOCK", 1);
        add.setExistingOrderCode(code);
        Order updated = service.createOrder(add);
        assertEquals(code, updated.getOrderCode());

        // Pero agregar por ENCARGO al mismo pedido si esta bloqueado.
        OrderRequest addConsolidado = pedido("CONSOLIDADO", 1);
        addConsolidado.setExistingOrderCode(code);
        assertThrows(IllegalArgumentException.class, () -> service.createOrder(addConsolidado));
    }

    @Test
    void noSePuedeCambiarElCanalDeUnPedidoAlAcumular() {
        // Un cliente NO debe poder mandar channel=STOCK sobre su pedido por encargo:
        // se saltaria el candado del plazo y sacaria del lote un pedido ya pagado.
        Consolidado c = consolidado("ABIERTO", Instant.now().minusSeconds(60), Instant.now().plus(1, ChronoUnit.DAYS));
        Order encargo = service.createOrder(pedido("CONSOLIDADO", 1));
        String code = encargo.getOrderCode();

        OrderRequest colado = pedido("STOCK", 1);
        colado.setExistingOrderCode(code);
        assertThrows(IllegalArgumentException.class, () -> service.createOrder(colado),
                "cambiar el canal de un pedido existente debe rechazarse");

        // El pedido conserva su canal original.
        assertEquals("CONSOLIDADO", orderRepo.findByOrderCode(code).orElseThrow().getChannel());
    }

    @Test
    void editarUnPedidoDeStockNoDependeDelPlazo() {
        // Los pedidos STOCK se anclan al consolidado mas nuevo aunque este cerrado:
        // el plazo del lote no puede bloquear la edicion de una venta inmediata.
        Consolidado c = consolidado("ABIERTO", Instant.now().minusSeconds(60), Instant.now().plus(1, ChronoUnit.DAYS));
        Order stock = service.createOrder(pedido("STOCK", 2));
        String code = stock.getOrderCode();

        c.setStatus("CERRADO");
        c.setEndsAt(Instant.now().minusSeconds(10));
        consolidadoRepo.save(c);

        OrderRequest.OrderItemRequest it = new OrderRequest.OrderItemRequest();
        it.setProductId(perfume.getId());
        it.setQuantity(1);
        Order edited = service.editOrderByClient(code, "999888777", List.of(it));
        assertEquals(1, edited.getItems().get(0).getQuantity(), "el cliente debe poder editar su pedido de stock");
    }

    // ================= 4. Scheduler =================

    @Test
    void schedulerCierraVencidosAbreProgramadosYRespetaLosSinPlazo() {
        Consolidado vencido = consolidado("ABIERTO", Instant.now().minus(5, ChronoUnit.DAYS), Instant.now().minusSeconds(10));
        Consolidado sinPlazo = consolidado("ABIERTO", null, null); // el consolidado historico de produccion

        scheduler.tick();

        assertEquals("CERRADO", consolidadoRepo.findById(vencido.getId()).orElseThrow().getStatus());
        assertNotNull(consolidadoRepo.findById(vencido.getId()).orElseThrow().getCloseDate());
        assertEquals("ABIERTO", consolidadoRepo.findById(sinPlazo.getId()).orElseThrow().getStatus(),
                "un ABIERTO sin plazo JAMAS se cierra solo");

        // Un PROGRAMADO cuya hora ya llego se abre.
        consolidadoRepo.deleteAll();
        Consolidado programado = consolidado("PROGRAMADO", Instant.now().minusSeconds(5), Instant.now().plus(2, ChronoUnit.DAYS));
        scheduler.tick();
        Consolidado abierto = consolidadoRepo.findById(programado.getId()).orElseThrow();
        assertEquals("ABIERTO", abierto.getStatus());
        assertNotNull(abierto.getOpenDate());

        // Uno programado a futuro NO se toca.
        consolidadoRepo.deleteAll();
        Consolidado futuro = consolidado("PROGRAMADO", Instant.now().plus(2, ChronoUnit.DAYS), Instant.now().plus(9, ChronoUnit.DAYS));
        scheduler.tick();
        assertEquals("PROGRAMADO", consolidadoRepo.findById(futuro.getId()).orElseThrow().getStatus());
    }

    // ================= 5. Apertura y extension de plazo =================

    @Test
    void abrirConsolidadoValidaDuplicadosYFechas() {
        Consolidado c = service.openConsolidado("Consolidado de Julio", "Cierra el viernes",
                null, Instant.now().plus(5, ChronoUnit.DAYS).toEpochMilli(), null);
        assertEquals("ABIERTO", c.getStatus());
        assertNotNull(c.getOpenDate());
        assertFalse(c.getExtended());

        // Ya hay uno abierto -> no se puede abrir otro.
        assertThrows(IllegalStateException.class, () -> service.openConsolidado("Otro", null,
                null, Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli(), null));

        // Cierre en el pasado -> invalido.
        consolidadoRepo.deleteAll();
        assertThrows(IllegalArgumentException.class, () -> service.openConsolidado("Malo", null,
                null, Instant.now().minus(1, ChronoUnit.DAYS).toEpochMilli(), null));

        // startAt futuro -> nace PROGRAMADO.
        Consolidado prog = service.openConsolidado("Agosto", null,
                Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli(),
                Instant.now().plus(6, ChronoUnit.DAYS).toEpochMilli(), null);
        assertEquals("PROGRAMADO", prog.getStatus());
    }

    @Test
    void extenderElPlazoMarcaExtendedYAcortarloNo() {
        Instant fin = Instant.now().plus(2, ChronoUnit.DAYS);
        Consolidado c = consolidado("ABIERTO", Instant.now().minusSeconds(60), fin);

        Consolidado acortado = service.updateSchedule(c.getId(), null, null, null,
                fin.minus(1, ChronoUnit.DAYS).toEpochMilli(), null);
        assertFalse(acortado.getExtended(), "acortar el plazo no es una extension");

        Consolidado extendido = service.updateSchedule(c.getId(), "Nuevo titulo", "desc", null,
                fin.plus(3, ChronoUnit.DAYS).toEpochMilli(), null);
        assertTrue(extendido.getExtended(), "mover el cierre hacia adelante = plazo extendido");
        assertEquals("Nuevo titulo", extendido.getTitle());
        assertTrue(service.getCurrentPublic().extended);

        // Un consolidado cerrado ya no se puede reprogramar.
        c.setStatus("CERRADO");
        consolidadoRepo.save(c);
        assertThrows(IllegalStateException.class, () -> service.updateSchedule(c.getId(), null, null, null,
                Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli(), null));
    }

    // ================= 6. Galeria de imagenes =================

    @Test
    void laImagenEnUsoNoSePuedeBorrarDeLaGaleria() {
        MediaImage img = new MediaImage();
        img.setName("banner.webp");
        img.setContentType("image/webp");
        img.setDataBase64("aGVsbG8=");
        img.setSizeBytes(5);
        img = mediaRepo.save(img);

        Consolidado c = consolidado("ABIERTO", Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS));
        c.setImageMediaId(img.getId());
        consolidadoRepo.save(c);

        assertTrue(consolidadoRepo.existsByImageMediaId(img.getId()),
                "la galeria debe detectar que la imagen esta en uso (anti-huerfanos)");

        c.setImageMediaId(null);
        consolidadoRepo.save(c);
        assertFalse(consolidadoRepo.existsByImageMediaId(img.getId()));
    }

    // ================= 7. El endpoint publico nunca crea =================

    @Test
    void elEstadoPublicoNuncaCreaConsolidados() {
        long antes = consolidadoRepo.count();
        assertEquals(0, antes);

        ConsolidadoPublicDTO dto = service.getCurrentPublic();
        assertEquals("NONE", dto.status);
        assertFalse(dto.open);
        assertNotNull(dto.serverNowMs, "el countdown necesita la hora del servidor");
        assertEquals(0, consolidadoRepo.count(), "una visita anonima JAMAS debe crear un consolidado");
    }

    @Test
    void elEstadoPublicoPrefiereAbiertoSobreCerradoYExponeElAviso() {
        consolidado("CERRADO", null, null);
        Consolidado abierto = service.openConsolidado("Julio 2026", "Aprovecha",
                null, Instant.now().plus(4, ChronoUnit.DAYS).toEpochMilli(), 7L);

        ConsolidadoPublicDTO dto = service.getCurrentPublic();
        assertEquals(abierto.getId(), dto.id);
        assertEquals("ABIERTO", dto.status);
        assertEquals("Julio 2026", dto.title);
        assertEquals("Aprovecha", dto.description);
        assertEquals(7L, dto.imageMediaId);
        assertTrue(dto.open);
        assertNotNull(dto.endsAtMs);
        assertTrue(dto.endsAtMs > dto.serverNowMs);
    }
}
