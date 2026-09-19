package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.backendbvaberiaperfumes.controller.ExcelFillController;
import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.SingleSupplierPlan;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
import org.example.backendbvaberiaperfumes.service.excelfill.SupplierExcelFiller;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Cableado del filtro NSO (parte 2): compras (asignacion, "comprar solo en un proveedor", relleno de tienda,
 * reporte de margen, plan borrador/confirmado), Excel del proveedor, faltantes e importacion (preview + commit).
 * Con el gate APAGADO todo se comporta como antes; con el gate ACTIVO lo que no tiene NSO no se compra y aparece
 * aparte en nsoBlocked. El import muestra el veredicto NSO por fila y re-verifica los perfumes al publicar.
 *
 * Catalogo sintetico de una marca inventada (KALDORIA). Sin @Transactional (commits reales). Tests en orden:
 * el primero verifica el import SIN lista NSO cargada. Correo apagado en las propiedades.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsowiringpurchaseimporttest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        // Nunca mandar correos reales desde el test (secret-mail.properties local trae la clave SMTP)
        "spring.mail.password=",
        "resend.api.key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NsoWiringPurchaseImportTest {

    static final String CSV = String.join("\n",
            "nso,categoria,marca,producto,ean,titular,ruc",
            "NSOC92001-25PE,Arabe,KALDORIA,AGUA DE PERFUME-EMBER VALLEY,,IMPORTADORA KALDORIA SAC,20123456789",
            "NSOC92002-25PE,Arabe,KALDORIA,AGUA DE TOCADOR-WINTER FERN,,IMPORTADORA KALDORIA SAC,20123456789",
            "NSOC92003-25PE,Arabe,KALDORIA,AGUA DE PERFUME-STONE GARDEN,,IMPORTADORA KALDORIA SAC,20123456789",
            "NSOC92004-25PE,Arabe,KALDORIA,AGUA DE PERFUME-AMBER COAST,,IMPORTADORA KALDORIA SAC,20123456789",
            "NSOC92005-25PE,Arabe,KALDORIA,AGUA DE PERFUME-IRON BLOOM,,IMPORTADORA KALDORIA SAC,20123456789") + "\n";

    static final String IMPORT_SUPPLIER = "NsoImportProveedor";
    static final String T_EMBER = "KALDORIA EMBER VALLEY 100ML EDP WOMEN";
    static final String T_FERN = "KALDORIA WINTER FERN 100ML EDP WOMEN";
    static final String T_VOYAGE = "KALDORIA SILENT VOYAGE 100ML EDP WOMEN";
    static final String T_DANCER = "BRUMOZA CLOUD DANCER 100ML EDP WOMEN";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NsoService nsoService;
    @Autowired NsoGate gate;
    @Autowired AllocationService allocationService;
    @Autowired ExcelImportService importService;
    @Autowired ExcelFillController excelFillController;
    @Autowired ProductRepository productRepo;
    @Autowired ProductNsoRepository productNsoRepo;
    @Autowired NsoRecordRepository recordRepo;
    @Autowired NsoCandidateRepository candidateRepo;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;
    @Autowired SupplierConstraintRepository constraintRepo;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired PurchasePlanRepository planRepo;
    @Autowired PlatformTransactionManager txManager;

    // ============================ helpers ============================

    private Product product(String brand, String name) {
        Product p = new Product();
        p.setSku("NSO-PI-" + UUID.randomUUID());
        p.setBrand(brand);
        p.setName(name);
        p.setMl(100);
        p.setWeightG(600);
        p.setAvailable(true);
        p.setArchived(false);
        p.setWholesalePricePen(150.0);
        p.setPriceUsd(20.0);
        return productRepo.save(p);
    }

    /** Perfume con NSO asignado a mano (bloqueado, deterministico). */
    private Product conNso(String name, String code) {
        Product p = product("Kaldoria", name);
        nsoService.assignManual(p.getId(), new NsoService.ManualCode(code));
        gate.invalidate();
        return p;
    }

    /** Perfume sin NSO: marca que no esta en la lista. */
    private Product sinNso(String name) {
        Product p = product("Brumoza", name);
        nsoService.rematchProducts(List.of(p.getId()));
        gate.invalidate();
        return p;
    }

    private void ensureCatalog() {
        if (recordRepo.count() > 0) return;
        nsoService.uploadCatalog("nso.csv", CSV.getBytes(StandardCharsets.UTF_8));
        assertTrue(nsoService.awaitRematchIdle(60_000), "el rematch en segundo plano debe terminar");
        gate.invalidate();
    }

    private String status(Product p) {
        return productNsoRepo.findById(p.getId()).map(ProductNso::getStatus).orElse(ProductNso.STATUS_SIN_VERIFICAR);
    }

    private void offer(Product p, Supplier s, double cost, String rawTitle) {
        SupplierOffer o = new SupplierOffer();
        o.setProduct(p);
        o.setSupplier(s);
        o.setOfferKey("NSO-PI-" + UUID.randomUUID());
        o.setCostUsd(cost);
        o.setInStock(true);
        o.setRawTitle(rawTitle);
        offerRepo.save(o);
    }

    private void setMinOrder(Supplier s, double min) {
        constraintRepo.findBySupplier_Id(s.getId()).stream()
                .filter(c -> "MIN_ORDER_USD".equals(c.getType()))
                .forEach(constraintRepo::delete);
        if (min > 0) constraintRepo.save(new SupplierConstraint(s, "MIN_ORDER_USD", min));
        s.setMinOrderUsd(min);
        supplierRepo.save(s);
    }

    private OrderItem item(org.example.backendbvaberiaperfumes.model.Order o, Product p, int qty, double unitPricePen) {
        OrderItem it = new OrderItem();
        it.setOrder(o);
        it.setProduct(p);
        it.setQuantity(qty);
        it.setUnitPricePen(unitPricePen);
        it.calculateSubtotal();
        return it;
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

    /**
     * Escenario de compra (consolidado nuevo, pedido SEPARADO):
     * - ok: CON_NSO, solo en Zimaxx ($30).          - blocked: SIN_NSO, solo en Magnet ($10), 2 u.
     * - blockedZx: SIN_NSO, solo en Zimaxx ($12).   - missingOk: CON_NSO sin ofertas.
     * - missingBlocked: sin fila NSO (SIN_VERIFICAR), sin ofertas, 3 u.
     * - fillOk / fillBlocked: ofertas caras en Zimaxx NO pedidas (candidatas a relleno de tienda).
     * Zimaxx con minimo $50: con el gate apagado compra 42 y con el gate activo 30 -> relleno de tienda.
     */
    private final class Scenario {
        final Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        final Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        final Product ok = conNso("Stone Garden 3.4 Oz Edp Women", "NSOC92003-25PE");
        final Product blocked = sinNso("Compra Bloqueada Uno");
        final Product blockedZx = sinNso("Compra Bloqueada Dos");
        final Product missingOk = conNso("Iron Bloom 3.4 Oz Edp Women", "NSOC92005-25PE");
        final Product missingBlocked = product("Brumoza", "Faltante Sin Verificar");
        final Product fillOk = conNso("Amber Coast 3.4 Oz Edp Women", "NSOC92004-25PE");
        final Product fillBlocked = sinNso("Relleno Bloqueado");
        final Consolidado con;

        Scenario() {
            setMinOrder(zimaxx, 50.0);
            setMinOrder(magnet, 0.0);
            offer(ok, zimaxx, 30.0, "KALDORIA STONE GARDEN 100ML EDP " + ok.getId());
            offer(blocked, magnet, 10.0, "BRUMOZA COMPRA BLOQUEADA UNO " + blocked.getId());
            offer(blockedZx, zimaxx, 12.0, "BRUMOZA COMPRA BLOQUEADA DOS " + blockedZx.getId());
            offer(fillOk, zimaxx, 998.0, null);
            offer(fillBlocked, zimaxx, 999.0, null);
            Consolidado c = new Consolidado();
            c.setStatus("ABIERTO");
            con = consolidadoRepo.save(c);
            org.example.backendbvaberiaperfumes.model.Order o = new org.example.backendbvaberiaperfumes.model.Order();
            o.setConsolidado(con);
            o.setClientName("Cliente Compra NSO");
            o.setClientPhone("955111222");
            o.setPaymentStatus("SEPARADO");
            o.getItems().add(item(o, ok, 1, 113.0));
            o.getItems().add(item(o, blocked, 2, 55.0));
            o.getItems().add(item(o, blockedZx, 1, 60.0));
            o.getItems().add(item(o, missingOk, 1, 100.0));
            o.getItems().add(item(o, missingBlocked, 3, 90.0));
            orderRepo.save(o);
        }

        Set<Long> blockedIds() {
            return Set.of(blocked.getId(), blockedZx.getId(), missingBlocked.getId());
        }
    }

    /** Productos de las lineas de un plan (las lineas son LAZY: se leen dentro de una transaccion). */
    private Set<Long> planProductIds(Long planId) {
        return new TransactionTemplate(txManager).execute(st -> planRepo.findById(planId).orElseThrow().getLines()
                .stream().map(PurchasePlanLine::getProductId).collect(Collectors.toSet()));
    }

    private static Set<Long> lineIds(AllocationResponse r) {
        return r.suppliers.stream().flatMap(g -> g.lines.stream()).map(l -> l.productId).collect(Collectors.toSet());
    }

    private byte[] importExcel() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Lista");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("DESCRIPTION");
            header.createCell(1).setCellValue("UPC");
            header.createCell(2).setCellValue("PRICE");
            String[][] rows = {
                    {T_EMBER, "25"},
                    {T_EMBER, "25"},      // duplicado real dentro del archivo: el preview lo salta
                    {T_FERN, "22"},
                    {T_VOYAGE, "20"},
                    {T_DANCER, "18"}};
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue(rows[i][0]);
                r.createCell(1).setCellValue("");
                r.createCell(2).setCellValue(Double.parseDouble(rows[i][1]));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private Supplier importSupplier() {
        return supplierRepo.findByName(IMPORT_SUPPLIER)
                .orElseGet(() -> supplierRepo.save(new Supplier(IMPORT_SUPPLIER, 0.0, false)));
    }

    private static ImportPreview.Line line(ImportPreview p, String rawTitle) {
        return p.rows.stream().filter(l -> rawTitle.equals(l.rawTitle)).findFirst().orElseThrow();
    }

    private long pendingCandidates() {
        return candidateRepo.findByStatusOrderByProductIdAscRankAscIdAsc(NsoCandidate.STATUS_PENDING).size();
    }

    // ============================ 1. import SIN lista NSO ============================

    @Test
    @Order(1)
    void importSinListaNsoNoTraeVeredictosNiEscribeNada() throws Exception {
        assertEquals(0, recordRepo.count());
        Supplier s = importSupplier();
        ExcelImportService.ParsedData pd = importService.parse(s, importExcel(), null);
        assertEquals(5, pd.rows.size());

        ImportPreview preview = importService.buildPreview(s, pd);
        assertEquals(4, preview.rows.size(), "el duplicado del archivo no se muestra dos veces");
        assertFalse(preview.nsoCatalogLoaded);
        assertFalse(preview.nsoGateEnabled);
        assertEquals(0, preview.nsoConNso + preview.nsoReview + preview.nsoBrandOnly + preview.nsoNone);
        for (ImportPreview.Line l : preview.rows) {
            assertNull(l.nsoStatus);
            assertNull(l.nsoCode);
            assertNull(l.nsoDeclaredName);
            assertNull(l.nsoTitular);
            assertNull(l.nsoReason);
            assertNull(l.nsoCountry);
        }

        ImportSummary summary = importService.commit(s, pd.rows, Set.of());
        assertEquals(4, summary.getProductsCreated());
        assertEquals(0, summary.getNsoConNso());
        assertEquals(0, summary.getNsoReview());
        assertEquals(0, summary.getNsoBrandOnly());
        assertEquals(0, summary.getNsoNone());
        assertTrue(summary.getNotes().stream().noneMatch(n -> n.contains("NSO")), String.valueOf(summary.getNotes()));
        assertEquals(0, productNsoRepo.count(), "sin lista NSO no se escribe estado");
        assertEquals(0, candidateRepo.count());

        // JSON del contrato
        JsonNode pj = json.valueToTree(preview);
        for (String k : List.of("nsoCatalogLoaded", "nsoGateEnabled", "nsoConNso", "nsoReview", "nsoBrandOnly", "nsoNone")) {
            assertTrue(pj.has(k), k);
        }
        JsonNode lj = pj.get("rows").get(0);
        for (String k : List.of("nsoStatus", "nsoCode", "nsoDeclaredName", "nsoTitular", "nsoReason", "nsoCountry")) {
            assertTrue(lj.has(k), k);
        }
        JsonNode sj = json.valueToTree(summary);
        for (String k : List.of("nsoConNso", "nsoReview", "nsoBrandOnly", "nsoNone")) assertTrue(sj.has(k), k);
    }

    // ============================ 2. import CON lista NSO ============================

    @Test
    @Order(2)
    void importConListaNsoTraeVeredictoPorFilaYPublicarReVerificaSinDuplicarCandidatos() throws Exception {
        ensureCatalog();
        Supplier s = importSupplier();
        byte[] bytes = importExcel();
        ExcelImportService.ParsedData pd = importService.parse(s, bytes, null);
        long filasAntes = productNsoRepo.count();
        long candidatosAntes = candidateRepo.count();

        ImportPreview preview = importService.buildPreview(s, pd);
        assertTrue(preview.nsoCatalogLoaded);
        assertFalse(preview.nsoGateEnabled);
        assertEquals(4, preview.rows.size());
        // Las filas van alineadas con lo que se muestra (el duplicado se salta y no corre los veredictos)
        ImportPreview.Line ember = line(preview, T_EMBER);
        assertEquals(ProductNso.STATUS_CON_NSO, ember.nsoStatus);
        assertEquals("NSOC92001-25PE", ember.nsoCode);
        assertEquals("AGUA DE PERFUME-EMBER VALLEY", ember.nsoDeclaredName);
        assertEquals("IMPORTADORA KALDORIA SAC", ember.nsoTitular);
        assertEquals("PE", ember.nsoCountry);
        ImportPreview.Line fern = line(preview, T_FERN);
        assertEquals(ProductNso.STATUS_EN_REVISION, fern.nsoStatus, "EDP vs agua de tocador va a revision");
        assertEquals("NSOC92002-25PE", fern.nsoCode, "el mejor candidato");
        assertEquals("AGUA DE TOCADOR-WINTER FERN", fern.nsoDeclaredName);
        assertNotNull(fern.nsoReason);
        ImportPreview.Line voyage = line(preview, T_VOYAGE);
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, voyage.nsoStatus);
        assertNull(voyage.nsoCode);
        assertEquals("IMPORTADORA KALDORIA SAC", voyage.nsoTitular);
        ImportPreview.Line dancer = line(preview, T_DANCER);
        assertEquals(ProductNso.STATUS_SIN_NSO, dancer.nsoStatus);
        assertNull(dancer.nsoCode);
        assertEquals(1, preview.nsoConNso, "el duplicado no se cuenta dos veces");
        assertEquals(1, preview.nsoReview);
        assertEquals(1, preview.nsoBrandOnly);
        assertEquals(1, preview.nsoNone);
        assertEquals(filasAntes, productNsoRepo.count(), "el preview no escribe");
        assertEquals(candidatosAntes, candidateRepo.count());

        // Con el filtro encendido el preview lo dice
        nsoService.setGate(true);
        try {
            assertTrue(importService.buildPreview(s, importService.parse(s, bytes, null)).nsoGateEnabled);
        } finally {
            nsoService.setGate(false);
        }

        // Publicar: re-verifica los perfumes tocados y llena el resumen
        ImportSummary summary = importService.commit(s, importService.parse(s, bytes, null).rows, Set.of());
        assertEquals(1, summary.getNsoConNso());
        assertEquals(1, summary.getNsoReview());
        assertEquals(1, summary.getNsoBrandOnly());
        assertEquals(1, summary.getNsoNone());
        assertTrue(summary.getNotes().stream().anyMatch(n ->
                n.contains("1 con NSO") && n.contains("1 por revisar") && n.contains("1 sin NSO")), String.valueOf(summary.getNotes()));
        Long fernId = offerRepo.findBySupplier_Id(s.getId()).stream()
                .filter(o -> T_FERN.equals(o.getRawTitle())).findFirst().orElseThrow().getProduct().getId();
        assertEquals(ProductNso.STATUS_EN_REVISION, productNsoRepo.findById(fernId).orElseThrow().getStatus());
        long pendientes = pendingCandidates();
        long candidatos = candidateRepo.count();
        assertTrue(pendientes >= 1);

        // Re-importar (endpoint viejo importExcel): mismos conteos, sin candidatos duplicados
        ImportSummary again = importService.importExcel(s.getId(), new ByteArrayInputStream(bytes));
        assertEquals(0, again.getProductsCreated());
        assertEquals(1, again.getNsoConNso());
        assertEquals(1, again.getNsoReview());
        assertEquals(1, again.getNsoBrandOnly());
        assertEquals(1, again.getNsoNone());
        assertEquals(pendientes, pendingCandidates(), "re-importar no duplica candidatos");
        assertEquals(candidatos, candidateRepo.count());
    }

    // ============================ 3. asignacion, un proveedor, relleno, margen ============================

    @Test
    @Order(3)
    void asignacionSeparaLaDemandaSinNsoYNoLaCompraNiLaSugiere() {
        ensureCatalog();
        Scenario sc = new Scenario();
        assertEquals(ProductNso.STATUS_CON_NSO, status(sc.ok));
        assertEquals(ProductNso.STATUS_SIN_NSO, status(sc.blocked));
        assertEquals(ProductNso.STATUS_SIN_VERIFICAR, status(sc.missingBlocked));

        // Gate apagado: todo como antes
        assertFalse(gate.isActive());
        AllocationResponse off = allocationService.computeAllocation(sc.con.getId());
        assertTrue(off.nsoBlocked.isEmpty());
        assertTrue(lineIds(off).containsAll(Set.of(sc.ok.getId(), sc.blocked.getId(), sc.blockedZx.getId())));
        assertTrue(off.unfulfillable.stream().anyMatch(u -> u.productId.equals(sc.missingBlocked.getId())));
        assertTrue(off.notes.stream().noneMatch(n -> n.contains("NSO")), String.valueOf(off.notes));
        Set<Long> fillOff = off.storeFillSuggestions.stream().map(f -> f.productId).collect(Collectors.toSet());
        assertTrue(fillOff.contains(sc.fillBlocked.getId()), "gate apagado: el relleno no se filtra " + off.notes);
        SingleSupplierPlan onlyOff = allocationService.consolidateToSupplier(sc.con.getId(), sc.zimaxx.getId());
        assertTrue(onlyOff.nsoBlocked.isEmpty());
        assertTrue(onlyOff.couldNotBuy.stream().anyMatch(c -> c.productId.equals(sc.blocked.getId())));
        Set<Long> marginOff = allocationService.marginReport(sc.con.getId()).stream()
                .map(m -> (Long) m.get("productId")).collect(Collectors.toSet());
        assertTrue(marginOff.contains(sc.blocked.getId()));

        nsoService.setGate(true);
        try {
            assertTrue(gate.isActive());
            AllocationResponse on = allocationService.computeAllocation(sc.con.getId());
            // Bloqueados: aparte, con unidades y estado; nunca en proveedores ni en "sin stock"
            Map<Long, AllocationResponse.NsoBlockedItem> byId = on.nsoBlocked.stream()
                    .collect(Collectors.toMap(b -> b.productId, b -> b));
            assertEquals(sc.blockedIds(), byId.keySet());
            AllocationResponse.NsoBlockedItem b = byId.get(sc.blocked.getId());
            assertEquals(2, b.quantity);
            assertEquals("Brumoza", b.brand);
            assertEquals("Compra Bloqueada Uno", b.name);
            assertEquals(100, b.ml);
            assertEquals(ProductNso.STATUS_SIN_NSO, b.status);
            assertEquals(3, byId.get(sc.missingBlocked.getId()).quantity);
            assertEquals(ProductNso.STATUS_SIN_VERIFICAR, byId.get(sc.missingBlocked.getId()).status);
            Set<Long> lines = lineIds(on);
            assertTrue(lines.contains(sc.ok.getId()));
            for (Long id : sc.blockedIds()) assertFalse(lines.contains(id), "bloqueado en proveedores: " + id);
            Set<Long> unf = on.unfulfillable.stream().map(u -> u.productId).collect(Collectors.toSet());
            assertTrue(unf.contains(sc.missingOk.getId()));
            assertFalse(unf.contains(sc.missingBlocked.getId()));
            assertTrue(on.notes.stream().anyMatch(n -> n.contains("3 perfume(s)") && n.contains("sin NSO")
                    && n.contains("6 unidad(es)")), String.valueOf(on.notes));

            // Relleno de tienda: nunca sugiere perfumes sin NSO
            Set<Long> fillOn = on.storeFillSuggestions.stream().map(f -> f.productId).collect(Collectors.toSet());
            assertTrue(fillOn.contains(sc.fillOk.getId()), "relleno con NSO sigue: " + on.notes);
            assertFalse(fillOn.contains(sc.fillBlocked.getId()));
            for (Long id : fillOn) assertTrue(gate.isPurchasable(id), "relleno sin NSO: " + id);

            // Comprar solo en Zimaxx: bloqueados aparte, ni en compra ni en "no se consigue"
            SingleSupplierPlan only = allocationService.consolidateToSupplier(sc.con.getId(), sc.zimaxx.getId());
            assertEquals(sc.blockedIds(), only.nsoBlocked.stream().map(x -> x.productId).collect(Collectors.toSet()));
            assertTrue(only.buy.stream().anyMatch(x -> x.productId.equals(sc.ok.getId())));
            for (Long id : sc.blockedIds()) {
                assertTrue(only.buy.stream().noneMatch(x -> x.productId.equals(id)));
                assertTrue(only.couldNotBuy.stream().noneMatch(x -> x.productId.equals(id)));
            }

            // Reporte de margen: misma forma (array), sin los bloqueados
            Set<Long> marginOn = allocationService.marginReport(sc.con.getId()).stream()
                    .map(m -> (Long) m.get("productId")).collect(Collectors.toSet());
            assertTrue(marginOn.contains(sc.ok.getId()));
            for (Long id : sc.blockedIds()) assertFalse(marginOn.contains(id));

            // JSON: nsoBlocked con la forma del contrato
            JsonNode j = json.valueToTree(only);
            JsonNode first = j.get("nsoBlocked").get(0);
            for (String k : List.of("productId", "brand", "name", "ml", "quantity", "status")) assertTrue(first.has(k), k);
        } finally {
            nsoService.setGate(false);
        }
    }

    // ============================ 4. plan borrador y confirmacion ============================

    @Test
    @Order(4)
    void planBorradorSoloTraeLineasPermitidasYUnBorradorViejoNoSeConfirma() throws Exception {
        ensureCatalog();
        Scenario sc = new Scenario();
        String t = token();

        // Borrador calculado con el filtro apagado: trae los perfumes sin NSO
        AllocationResponse viejo = allocationService.computeAndSaveDraft(sc.con.getId());
        Set<Long> viejoIds = planProductIds(viejo.planId);
        assertTrue(viejoIds.contains(sc.blocked.getId()));

        nsoService.setGate(true);
        try {
            MockHttpServletResponse res = call(post("/api/admin/consolidados/" + sc.con.getId() + "/allocation/"
                    + viejo.planId + "/confirm?force=true").header("Authorization", "Bearer " + t));
            assertEquals(400, res.getStatus(), res.getContentAsString());
            JsonNode err = body(res);
            assertTrue(err.get("message").asText().contains("Vuelve a calcular"), err.toString());
            Set<Long> ids = new HashSet<>();
            for (JsonNode n : err.get("unavailableProductIds")) ids.add(n.asLong());
            assertEquals(Set.of(sc.blocked.getId(), sc.blockedZx.getId()), ids);
            assertEquals("DRAFT", planRepo.findById(viejo.planId).orElseThrow().getStatus(), "no se confirmo");

            // Borrador nuevo con el filtro activo: solo lineas permitidas; se confirma
            AllocationResponse nuevo = allocationService.computeAndSaveDraft(sc.con.getId());
            assertFalse(nuevo.nsoBlocked.isEmpty());
            Set<Long> nuevoIds = planProductIds(nuevo.planId);
            assertTrue(nuevoIds.contains(sc.ok.getId()));
            for (Long id : sc.blockedIds()) assertFalse(nuevoIds.contains(id), "linea sin NSO en el plan: " + id);
            PurchasePlan confirmado = allocationService.confirmPlan(sc.con.getId(), nuevo.planId, true);
            assertEquals("CONFIRMED", confirmado.getStatus());
        } finally {
            nsoService.setGate(false);
        }
    }

    // ============================ 5. faltantes y Excel del proveedor ============================

    @Test
    @Order(5)
    void faltantesYExcelDelProveedorNoIncluyenPerfumesSinNso() throws Exception {
        ensureCatalog();
        Scenario sc = new Scenario();
        String t = token();
        String missingUrl = "/api/admin/consolidados/" + sc.con.getId() + "/missing";

        Set<Long> missingOff = new HashSet<>();
        for (JsonNode n : body(call(get(missingUrl).header("Authorization", "Bearer " + t)))) missingOff.add(n.get("productId").asLong());
        assertEquals(Set.of(sc.missingOk.getId(), sc.missingBlocked.getId()), missingOff, "gate apagado: como antes");
        assertEquals(2, buildOrderLines(sc, true).size(), "gate apagado: el Excel pide todo");
        assertEquals(2, fillExcel(sc, t).get("found").asInt());

        nsoService.setGate(true);
        try {
            Set<Long> missingOn = new HashSet<>();
            for (JsonNode n : body(call(get(missingUrl).header("Authorization", "Bearer " + t)))) missingOn.add(n.get("productId").asLong());
            assertEquals(Set.of(sc.missingOk.getId()), missingOn, "los sin NSO salen en nsoBlocked, no como faltantes");

            // Filtro defensivo de buildOrderLines: aunque le llegue un perfume sin NSO, no lo pide
            List<SupplierExcelFiller.OrderLine> lines = buildOrderLines(sc, true);
            assertEquals(1, lines.size());
            assertEquals("Stone Garden 3.4 Oz Edp Women", lines.get(0).name);
            assertEquals("KALDORIA STONE GARDEN 100ML EDP " + sc.ok.getId(), lines.get(0).title);

            // De punta a punta: el Excel devuelto solo pide lo que tiene NSO
            JsonNode report = fillExcel(sc, t);
            assertEquals(1, report.get("found").asInt(), report.toString());
            assertEquals(0, report.get("notFound").size(), report.toString());
            assertEquals(1, report.get("hiddenRows").asInt());
        } finally {
            nsoService.setGate(false);
        }
    }

    /** Invoca el buildOrderLines privado con una demanda que incluye un perfume sin NSO (ok + blockedZx). */
    @SuppressWarnings("unchecked")
    private List<SupplierExcelFiller.OrderLine> buildOrderLines(Scenario sc, boolean withBlocked) throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(excelFillController);
        Class<?> demandType = Class.forName(ExcelFillController.class.getName() + "$Demand");
        Constructor<?> ctor = demandType.getDeclaredConstructor(Long.class, String.class, String.class, String.class, int.class);
        ctor.setAccessible(true);
        List<Object> demand = new ArrayList<>();
        demand.add(ctor.newInstance(sc.ok.getId(), null, sc.ok.getBrand(), sc.ok.getName(), 1));
        if (withBlocked) demand.add(ctor.newInstance(sc.blockedZx.getId(), null, sc.blockedZx.getBrand(), sc.blockedZx.getName(), 1));
        Method m = ExcelFillController.class.getDeclaredMethod("buildOrderLines", List.class, Long.class);
        m.setAccessible(true);
        return (List<SupplierExcelFiller.OrderLine>) m.invoke(target, demand, sc.zimaxx.getId());
    }

    /** POST fill-excel con un Excel estilo Zimaxx que trae las filas de ok y blockedZx. Devuelve el report. */
    private JsonNode fillExcel(Scenario sc, String token) throws Exception {
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Price List");
            sh.createRow(0).createCell(0).setCellValue("ZIMAXX - lista");
            String[] header = {"UPC", "Sku", "Brand", "Title Product", "Price", "Type", "Qty", "Total"};
            Row h = sh.createRow(1);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            Row r1 = sh.createRow(2);
            r1.createCell(2).setCellValue("KALDORIA");
            r1.createCell(3).setCellValue("KALDORIA STONE GARDEN 100ML EDP " + sc.ok.getId());
            r1.createCell(4).setCellValue(30.0);
            Row r2 = sh.createRow(3);
            r2.createCell(2).setCellValue("BRUMOZA");
            r2.createCell(3).setCellValue("BRUMOZA COMPRA BLOQUEADA DOS " + sc.blockedZx.getId());
            r2.createCell(4).setCellValue(12.0);
            wb.write(out);
            xlsx = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "zimaxx.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
        MockHttpServletResponse res = call(multipart("/api/admin/consolidados/" + sc.con.getId() + "/suppliers/"
                + sc.zimaxx.getId() + "/fill-excel").file(file).header("Authorization", "Bearer " + token));
        assertEquals(200, res.getStatus(), res.getContentAsString());
        return body(res).get("report");
    }
}
