package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.nso.NsoBlockedException;
import org.example.backendbvaberiaperfumes.service.nso.NsoConflictException;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.example.backendbvaberiaperfumes.service.nso.NsoNotFoundException;
import org.example.backendbvaberiaperfumes.service.nso.NsoService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Servicio NSO de punta a punta contra H2 (commits reales, sin @Transactional en el test): carga de la lista,
 * rematch en segundo plano y sincrono, decisiones de la admin (aprobar / ninguno / asignar / quitar), bloqueo que
 * sobrevive re-cargas, activar/desactivar codigos, alta manual, merge/borrado, gate y preview de import.
 *
 * Catalogo sintetico de una marca inventada (VELMORA) para no chocar con los 86 productos del seed.
 * Los tests van en orden: el primero verifica el estado SIN lista cargada.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsoserviceintegrationtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        "spring.mail.password=",
        "resend.api.key=",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "org.example.backendbvaberiaperfumes.NsoRepositoriesTest$SqlSpy"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NsoServiceIntegrationTest {

    static final String CSV = String.join("\n",
            "nso,categoria,marca,producto,ean,titular,ruc",
            "NSOC90001-25PE,Arabe,VELMORA,AGUA DE PERFUME-MOONLIGHT ROSE,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC90002-25PE,Arabe,VELMORA,AGUA DE TOCADOR-AMBER NIGHT,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC90003-25PE,Arabe,VELMORA,AGUA DE PERFUME,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC90004-24CO,Arabe,VELMORA,AGUA DE PERFUME-SILVER STORM,,,",
            "NSOC90005-25PE,Arabe,VELMORA,AGUA DE PERFUME-OCEAN BREEZE,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC90006-25PE,Arabe,VELMORA,AGUA DE PERFUME-CEDAR TRAIL,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC90007-25PE,Arabe,VELMORA,AGUA DE PERFUME-VELVET SKY,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC90008-25PE,Arabe,VELMORA,AGUA DE PERFUME-GOLDEN SAND,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC90009-25PE,Arabe,VELMORA,AGUA DE PERFUME-MISTY ORCHID,,IMPORTADORA DEMO SAC,20123456789",
            "NSOC123-25PE,Arabe,VELMORA,CODIGO ROTO,,,") + "\n";

    @Autowired NsoService service;
    @Autowired NsoGate gate;
    @Autowired ProductRepository productRepo;
    @Autowired ProductNsoRepository productNsoRepo;
    @Autowired NsoRecordRepository recordRepo;
    @Autowired NsoAliasRepository aliasRepo;
    @Autowired NsoCandidateRepository candidateRepo;
    @Autowired NsoEventRepository eventRepo;
    @Autowired AppConfigRepository configRepo;
    @Autowired SupplierRepository supplierRepo;

    // ============================ helpers ============================

    private Product product(String brand, String name) {
        Product p = new Product();
        p.setSku("NSO-IT-" + UUID.randomUUID());
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

    private Product velmora(String name) {
        return product("Velmora", name);
    }

    private Map<String, Object> upload(String csv) {
        Map<String, Object> r = service.uploadCatalog("nso.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertTrue(service.awaitRematchIdle(60_000), "el rematch en segundo plano debe terminar");
        return r;
    }

    private void ensureCatalog() {
        if (recordRepo.count() == 0) upload(CSV);
    }

    private ProductNso state(Product p) {
        return productNsoRepo.findById(p.getId()).orElse(null);
    }

    private String status(Product p) {
        ProductNso st = state(p);
        return st == null ? ProductNso.STATUS_SIN_VERIFICAR : st.getStatus();
    }

    private void rematch(Product... ps) {
        List<Long> ids = new ArrayList<>();
        for (Product p : ps) ids.add(p.getId());
        service.rematchProducts(ids);
    }

    // ============================ 1. sin lista ============================

    @Test
    @Order(1)
    void sinListaElGateNoSePuedeActivarYElResumenLoDice() {
        assertEquals(0, recordRepo.count());
        Product sinLista = velmora("Sin Lista Edp Women");
        assertEquals(0, service.rematchProducts(List.of(sinLista.getId())).getProcessed());
        assertNull(state(sinLista), "sin lista cargada no se escribe estado: sigue SIN_VERIFICAR");
        NsoConflictException ex = assertThrows(NsoConflictException.class, () -> service.setGate(true));
        assertTrue(ex.getMessage().contains("lista"), ex.getMessage());

        Map<String, Object> s = service.summary();
        assertEquals(false, s.get("gateEnabled"));
        assertEquals(false, s.get("gateEffective"));
        assertEquals(0L, s.get("catalogRecords"));
        assertNull(s.get("lastUpload"));
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) s.get("counts");
        assertEquals(ProductNso.STATUSES, new ArrayList<>(counts.keySet()));
        assertEquals(productRepo.countByArchivedFalse(), counts.get(ProductNso.STATUS_SIN_VERIFICAR));
        assertFalse(gate.isActive());
    }

    // ============================ 2. carga + rematch ============================

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void cargaLaListaYElRematchClasificaCadaPerfume() {
        Product moon = velmora("Moonlight Rose 3.4 Oz Edp Women");
        Product amber = velmora("Amber Night 3.4 Oz Edp Men");
        Product amber50 = velmora("Amber Night 1.7 Oz Edp Men");
        Product dune = velmora("Golden Dune Edp Men");
        Product storm = velmora("Silver Storm Edp Men");
        Product fantasma = product("Marca Fantasma", "Cosa Rara Edp");

        // El trabajador del rematch masivo pasa por el proxy @Async (no bloquea la peticion de subida).
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        assertTrue(java.util.Arrays.stream(((org.springframework.aop.framework.Advised) service).getAdvisors())
                .anyMatch(a -> a instanceof org.springframework.scheduling.annotation.AsyncAnnotationAdvisor));

        Map<String, Object> r = upload(CSV);
        assertEquals("CSV", r.get("fileType"));
        assertEquals("nso.csv", r.get("filename"));
        assertEquals(9, r.get("recordsRead"));
        assertEquals(9, r.get("inserted"));
        assertEquals(0, r.get("updated"));
        assertEquals(0, r.get("unchanged"));
        assertEquals(1, ((List<?>) r.get("invalidRows")).size());
        assertEquals(0, r.get("missingFromFile"));
        assertEquals(0, r.get("researchLinksRead"));
        assertEquals(1, r.get("catalogVersion"));
        assertEquals(true, r.get("rematchStarted"));

        assertEquals(ProductNso.STATUS_CON_NSO, status(moon));
        assertEquals("NSOC90001-25PE", state(moon).getNsoCode());
        assertEquals(ProductNso.MATCHED_NOMBRE, state(moon).getMatchedBy());
        assertEquals(ProductNso.STATUS_EN_REVISION, status(amber), "EDP vs agua de tocador (EDT) va a revision");
        assertEquals(ProductNso.STATUS_EN_REVISION, status(amber50));
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, status(dune));
        assertEquals(ProductNso.STATUS_CON_NSO, status(storm));
        assertEquals("NSOC90004-24CO", state(storm).getNsoCode());
        assertEquals(ProductNso.STATUS_SIN_NSO, status(fantasma));
        assertEquals(1, candidateRepo.findByProductIdAndStatusOrderByRankAscIdAsc(amber.getId(), NsoCandidate.STATUS_PENDING).size());

        Map<String, Object> s = service.summary();
        Map<String, Long> counts = (Map<String, Long>) s.get("counts");
        assertEquals(productRepo.countByArchivedFalse(), counts.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(0L, counts.get(ProductNso.STATUS_SIN_VERIFICAR), "el rematch masivo evaluo todo");
        assertTrue((Long) s.get("pendingCandidates") >= 2);
        assertTrue((Long) s.get("publicIfActivated") >= 2);
        assertEquals(9L, s.get("catalogActiveRecords"));
        Map<String, Object> last = (Map<String, Object>) s.get("lastUpload");
        assertEquals("nso.csv", last.get("filename"));
        assertNotNull(last.get("at"));
        Map<String, Object> progress = (Map<String, Object>) s.get("rematch");
        assertEquals(false, progress.get("running"));
        assertEquals(progress.get("total"), progress.get("processed"));
        assertNull(progress.get("error"));

        // Re-carga identica: idempotente
        Map<String, Object> again = upload(CSV);
        assertEquals(0, again.get("inserted"));
        assertEquals(9, again.get("unchanged"));
        assertEquals(2, again.get("catalogVersion"));
        assertEquals(1, candidateRepo.findByProductIdOrderByRankAscIdAsc(amber.getId()).size(), "sin candidatos duplicados");

        // Archivo sin registros validos: no marca ausentes
        Map<String, Object> soloInvalidas = upload("nso,marca,producto\nINVALIDO,VELMORA,ALGO\n");
        assertEquals(0, soloInvalidas.get("recordsRead"));
        assertEquals(0, soloInvalidas.get("missingFromFile"));
        assertTrue(recordRepo.findById("NSOC90001-25PE").orElseThrow().getInLastUpload());

        // Archivo sin un codigo: queda como aviso, no se borra
        String sinOrchid = CSV.replace("NSOC90009-25PE,Arabe,VELMORA,AGUA DE PERFUME-MISTY ORCHID,,IMPORTADORA DEMO SAC,20123456789\n", "");
        Map<String, Object> parcial = upload(sinOrchid);
        assertEquals(1, parcial.get("missingFromFile"));
        NsoRecord orchid = recordRepo.findById("NSOC90009-25PE").orElseThrow();
        assertFalse(orchid.getInLastUpload());
        assertTrue(orchid.getActive());
        List<Map<String, Object>> avisos = (List<Map<String, Object>>) service.summary().get("codesNotInLastUpload");
        assertTrue(avisos.stream().anyMatch(m -> "NSOC90009-25PE".equals(m.get("code"))));
        upload(CSV);
        assertTrue(recordRepo.findById("NSOC90009-25PE").orElseThrow().getInLastUpload());
        assertTrue(eventRepo.recent(null, NsoEvent.TYPE_CATALOG_UPLOAD, 10).size() >= 5);
    }

    // ============================ 3. aprobar ============================

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void aprobarBloqueaGuardaAliasResuelveOtrosTamanosYSobreviveRecarga() {
        ensureCatalog();
        Product amber = productRepo.findAll().stream()
                .filter(p -> "Amber Night 3.4 Oz Edp Men".equals(p.getName())).findFirst().orElseThrow();
        Product amber50 = productRepo.findAll().stream()
                .filter(p -> "Amber Night 1.7 Oz Edp Men".equals(p.getName())).findFirst().orElseThrow();

        List<Map<String, Object>> review = service.review();
        Map<String, Object> item = review.stream()
                .filter(i -> amber.getId().equals(((Map<String, Object>) i.get("product")).get("id")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> cands = (List<Map<String, Object>>) item.get("candidates");
        assertEquals("NSOC90002-25PE", cands.get(0).get("nsoCode"));
        assertEquals("IMPORTADORA DEMO SAC", cands.get(0).get("titular"));
        assertFalse(((List<String>) item.get("reasons")).isEmpty());
        Long candidateId = ((Number) cands.get(0).get("id")).longValue();

        Map<String, Object> res = service.accept(candidateId);
        assertEquals(amber.getId(), res.get("productId"));
        assertEquals(ProductNso.STATUS_CON_NSO, res.get("status"));
        assertEquals("NSOC90002-25PE", res.get("nsoCode"));
        assertEquals(List.of(amber50.getId()), res.get("alsoResolved"), "el otro tamano se resuelve solo");

        ProductNso st = state(amber);
        assertTrue(st.getLocked());
        assertEquals(ProductNso.MATCHED_APROBADO, st.getMatchedBy());
        assertEquals(NsoService.SYSTEM_ACTOR, st.getDecidedBy());
        assertEquals(ProductNso.MATCHED_ALIAS_NOMBRE, state(amber50).getMatchedBy());
        assertFalse(state(amber50).getLocked());
        assertEquals(NsoCandidate.STATUS_ACCEPTED, candidateRepo.findById(candidateId).orElseThrow().getStatus());
        assertTrue(aliasRepo.findBySourceProductId(amber.getId()).stream().anyMatch(a ->
                NsoAlias.KIND_NAME_KEY.equals(a.getKind()) && a.isPositive() && "NSOC90002-25PE".equals(a.getNsoCode())
                        && NsoAlias.ORIGIN_APROBADO.equals(a.getOrigin())));
        assertThrows(NsoConflictException.class, () -> service.accept(candidateId), "no se aprueba dos veces");
        assertEquals(1, eventRepo.recent(amber.getId(), NsoEvent.TYPE_CANDIDATE_ACCEPTED, 10).size());

        upload(CSV);
        st = state(amber);
        assertEquals(ProductNso.STATUS_CON_NSO, st.getStatus());
        assertEquals("NSOC90002-25PE", st.getNsoCode());
        assertTrue(st.getLocked(), "la decision sobrevive a re-subir la lista");
        assertEquals(ProductNso.MATCHED_APROBADO, st.getMatchedBy());
    }

    // ============================ 4. ninguno ============================

    @Test
    @Order(4)
    void ningunoRechazaSinBloquearYNoVuelveAProponer() {
        ensureCatalog();
        Product ocean = velmora("Ocean Breeze Intense Edp Men");
        rematch(ocean);
        assertEquals(ProductNso.STATUS_EN_REVISION, status(ocean));

        Map<String, Object> res = service.rejectAll(ocean.getId());
        assertEquals(ocean.getId(), res.get("productId"));
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, res.get("status"));
        assertFalse(state(ocean).getLocked(), "rechazar no bloquea");
        NsoCandidate c = candidateRepo.findByProductIdAndNsoCode(ocean.getId(), "NSOC90005-25PE").orElseThrow();
        assertEquals(NsoCandidate.STATUS_REJECTED, c.getStatus());
        assertTrue(aliasRepo.findBySourceProductId(ocean.getId()).stream().anyMatch(a ->
                NsoAlias.KIND_NAME_KEY.equals(a.getKind()) && !a.isPositive() && "NSOC90005-25PE".equals(a.getNsoCode())));

        service.startRematchAll();
        assertTrue(service.awaitRematchIdle(60_000));
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, status(ocean));
        assertEquals(1, candidateRepo.findByProductIdOrderByRankAscIdAsc(ocean.getId()).size(), "no se re-crea el rechazado");
        assertTrue(candidateRepo.findByProductIdAndStatusOrderByRankAscIdAsc(ocean.getId(), NsoCandidate.STATUS_PENDING).isEmpty());
    }

    // ============================ 5. quitar / asignar ============================

    @Test
    @Order(5)
    void quitarDesbloqueaRechazaElParYLaAdminPuedeCambiarDeOpinion() {
        ensureCatalog();
        Product cedar = velmora("Cedar Trail Edp Men");
        rematch(cedar);
        assertEquals("NSOC90006-25PE", state(cedar).getNsoCode());

        Map<String, Object> res = service.unassign(cedar.getId());
        assertNotEquals(ProductNso.STATUS_CON_NSO, res.get("status"));
        assertFalse(state(cedar).getLocked());
        assertEquals(NsoCandidate.STATUS_REJECTED,
                candidateRepo.findByProductIdAndNsoCode(cedar.getId(), "NSOC90006-25PE").orElseThrow().getStatus());
        assertEquals(1, eventRepo.recent(cedar.getId(), NsoEvent.TYPE_UNASSIGN, 10).size());

        upload(CSV);
        assertNotEquals(ProductNso.STATUS_CON_NSO, status(cedar), "quitar persiste aunque se recargue la lista");

        Map<String, Object> assigned = service.assignManual(cedar.getId(), new NsoService.ManualCode("nsoc 90006 - 25 pe"));
        assertEquals(ProductNso.STATUS_CON_NSO, assigned.get("status"));
        assertEquals("NSOC90006-25PE", assigned.get("nsoCode"));
        ProductNso st = state(cedar);
        assertTrue(st.getLocked());
        assertEquals(ProductNso.MATCHED_MANUAL, st.getMatchedBy());
        assertEquals(NsoCandidate.STATUS_ACCEPTED,
                candidateRepo.findByProductIdAndNsoCode(cedar.getId(), "NSOC90006-25PE").orElseThrow().getStatus());
        assertTrue(aliasRepo.findBySourceProductId(cedar.getId()).stream()
                .filter(a -> "NSOC90006-25PE".equals(a.getNsoCode()))
                .allMatch(a -> a.isPositive() && NsoAlias.ORIGIN_MANUAL.equals(a.getOrigin())));
    }

    // ============================ 6. activar / desactivar codigo ============================

    @Test
    @Order(6)
    @SuppressWarnings("unchecked")
    void desactivarCodigoPrevisualizaConfirmaDesbloqueaYReactivar() {
        ensureCatalog();
        Product sky = velmora("Velvet Sky Edp Women");
        Product sky50 = velmora("Velvet Sky 1.7 Oz Edp Women");
        rematch(sky, sky50);
        service.assignManual(sky.getId(), new NsoService.ManualCode("NSOC90007-25PE"));
        assertEquals(ProductNso.STATUS_CON_NSO, status(sky50));

        Map<String, Object> preview = service.setCodeActive("NSOC90007-25PE", false, false);
        assertEquals(false, preview.get("applied"));
        List<Map<String, Object>> affected = (List<Map<String, Object>>) preview.get("affectedProducts");
        assertEquals(2, affected.size());
        assertTrue(recordRepo.findById("NSOC90007-25PE").orElseThrow().getActive(), "previsualizar no cambia nada");

        Map<String, Object> applied = service.setCodeActive("NSOC90007-25PE", false, true);
        assertEquals(true, applied.get("applied"));
        assertFalse(recordRepo.findById("NSOC90007-25PE").orElseThrow().getActive());
        assertNotEquals(ProductNso.STATUS_CON_NSO, status(sky));
        assertNotEquals(ProductNso.STATUS_CON_NSO, status(sky50));
        assertFalse(state(sky).getLocked(), "un codigo desactivado no hereda el bloqueo");
        assertFalse(gate.conNsoIds().contains(sky.getId()));
        assertEquals(1, eventRepo.recent(null, NsoEvent.TYPE_CODE_DEACTIVATED, 10).size());

        service.setCodeActive("NSOC90007-25PE", true, true);
        assertEquals(ProductNso.STATUS_CON_NSO, status(sky));
        assertEquals(ProductNso.STATUS_CON_NSO, status(sky50));
        assertTrue(gate.conNsoIds().contains(sky50.getId()));
        assertThrows(NsoNotFoundException.class, () -> service.setCodeActive("NSOC99999-25PE", false, false));
        assertThrows(IllegalArgumentException.class, () -> service.setCodeActive("no-es-un-codigo", false, false));
    }

    // ============================ 7. alta manual ============================

    @Test
    @Order(7)
    @SuppressWarnings("unchecked")
    void codigosYPerfumesAMano() {
        ensureCatalog();
        // assign con codigo invalido / inexistente / crear
        Product lotus = velmora("Night Lotus Edp Women");
        rematch(lotus);
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, status(lotus));
        assertThrows(IllegalArgumentException.class, () -> service.assignManual(lotus.getId(), new NsoService.ManualCode("ABC")));
        NsoNotFoundException nf = assertThrows(NsoNotFoundException.class,
                () -> service.assignManual(lotus.getId(), new NsoService.ManualCode("NSOC90011-25PE")));
        assertTrue(nf.isCanCreate());
        NsoService.ManualCode crear = new NsoService.ManualCode("NSOC90011-25PE");
        crear.createIfMissing = true;
        crear.declaredName = "AGUA DE PERFUME-NIGHT LOTUS";
        crear.titular = "IMPORTADORA DEMO SAC";
        crear.ruc = "20123456789";
        assertEquals(ProductNso.STATUS_CON_NSO, service.assignManual(lotus.getId(), crear).get("status"));
        NsoRecord creado = recordRepo.findById("NSOC90011-25PE").orElseThrow();
        assertEquals(NsoRecord.SOURCE_MANUAL, creado.getSource());
        assertEquals("PE", creado.getCountry());

        // POST /catalog: re-verifica en el momento los perfumes de la marca
        Product rain = velmora("Crystal Rain Edp Women");
        rematch(rain);
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, status(rain));
        NsoService.NewRecord nuevo = new NsoService.NewRecord();
        nuevo.code = "NSOC90010-25PE";
        nuevo.brand = "VELMORA";
        nuevo.declaredName = "AGUA DE PERFUME-CRYSTAL RAIN";
        nuevo.titular = "IMPORTADORA DEMO SAC";
        nuevo.ruc = "20123456789";
        Map<String, Object> added = service.addCatalogRecord(nuevo);
        Map<String, Object> record = (Map<String, Object>) added.get("record");
        assertEquals("NSOC90010-25PE", record.get("code"));
        assertEquals(NsoRecord.SOURCE_MANUAL, record.get("source"));
        assertEquals(1L, record.get("linkedProducts"));
        assertTrue((Integer) added.get("rematched") >= 1);
        List<Map<String, Object>> linked = (List<Map<String, Object>>) added.get("linkedProducts");
        assertTrue(linked.stream().anyMatch(m -> rain.getId().equals(m.get("id"))
                && ProductNso.STATUS_CON_NSO.equals(m.get("status"))), linked.toString());
        assertEquals(ProductNso.STATUS_CON_NSO, status(rain));
        assertThrows(NsoConflictException.class, () -> service.addCatalogRecord(nuevo));

        // POST /api/admin/products: todo en una transaccion, el codigo se valida antes de crear
        long antes = productRepo.count();
        Product p1 = nuevoProducto("NSO-ALTA-1", "Moonlight Rose Edp Women");
        NsoService.ManualCode malo = new NsoService.ManualCode("XYZ");
        assertThrows(IllegalArgumentException.class, () -> service.createProductWithNso(p1, malo));
        NsoNotFoundException nf2 = assertThrows(NsoNotFoundException.class,
                () -> service.createProductWithNso(nuevoProducto("NSO-ALTA-1", "Moonlight Rose Edp Women"),
                        new NsoService.ManualCode("NSOC90012-25PE")));
        assertTrue(nf2.isCanCreate());
        assertEquals(antes, productRepo.count(), "nada se creo si el codigo no valida");

        Map<String, Object> ok = service.createProductWithNso(nuevoProducto("NSO-ALTA-1", "Moonlight Rose Edp Women"),
                new NsoService.ManualCode("NSOC90001-25PE"));
        Product creadoP = (Product) ok.get("product");
        assertNotNull(creadoP.getId());
        Map<String, Object> nso = (Map<String, Object>) ok.get("nso");
        assertEquals(ProductNso.STATUS_CON_NSO, nso.get("status"));
        assertEquals("NSOC90001-25PE", nso.get("nsoCode"));
        assertEquals(ProductNso.MATCHED_MANUAL, state(creadoP).getMatchedBy());
        assertTrue(state(creadoP).getLocked());
        assertThrows(NsoConflictException.class, () -> service.createProductWithNso(
                nuevoProducto("NSO-ALTA-1", "Otro"), null), "SKU duplicado");

        Map<String, Object> sinNso = service.createProductWithNso(nuevoProducto("NSO-ALTA-2", "Misty Orchid Edp Women"), null);
        Map<String, Object> nso2 = (Map<String, Object>) sinNso.get("nso");
        assertEquals(ProductNso.STATUS_CON_NSO, nso2.get("status"), "sin codigo: se verifica solo");
        assertEquals("NSOC90009-25PE", nso2.get("nsoCode"));
    }

    private Product nuevoProducto(String sku, String name) {
        Product p = new Product();
        p.setSku(sku);
        p.setBrand("Velmora");
        p.setName(name);
        p.setType("Women EDP");
        p.setMl(100);
        p.setPriceUsd(25.0);
        p.setWeightG(600);
        p.setCategory("women");
        p.setAvailable(true);
        return p;
    }

    // ============================ 8. merge y borrado ============================

    @Test
    @Order(8)
    void fusionPasaLaDecisionYLosRechazosAlCanonicoYBorrarLimpia() {
        ensureCatalog();
        Product canonico = velmora("Golden Sand Edp Men");
        Product duplicado = velmora("Golden Sand 3.4 Oz Edp Men");
        rematch(canonico, duplicado);
        service.assignManual(duplicado.getId(), new NsoService.ManualCode("NSOC90008-25PE"));
        NsoCandidate rechazado = new NsoCandidate(duplicado.getId(), "NSOC90005-25PE", 0.7, 2, NsoCandidate.ORIGIN_MATCHER);
        rechazado.setStatus(NsoCandidate.STATUS_REJECTED);
        candidateRepo.save(rechazado);
        assertFalse(state(canonico).getLocked());

        duplicado.setArchived(true);
        duplicado.setAvailable(false);
        productRepo.save(duplicado);
        service.onMerge(canonico.getId(), duplicado.getId());

        ProductNso st = state(canonico);
        assertEquals(ProductNso.STATUS_CON_NSO, st.getStatus());
        assertEquals("NSOC90008-25PE", st.getNsoCode());
        assertTrue(st.getLocked());
        assertEquals(ProductNso.MATCHED_MANUAL, st.getMatchedBy());
        assertEquals(NsoCandidate.STATUS_REJECTED,
                candidateRepo.findByProductIdAndNsoCode(canonico.getId(), "NSOC90005-25PE").orElseThrow().getStatus());
        assertNull(state(duplicado));
        assertTrue(candidateRepo.findByProductIdOrderByRankAscIdAsc(duplicado.getId()).isEmpty());
        assertTrue(aliasRepo.findBySourceProductId(duplicado.getId()).isEmpty());
        assertFalse(aliasRepo.findBySourceProductId(canonico.getId()).isEmpty());
        assertEquals(1, eventRepo.recent(canonico.getId(), NsoEvent.TYPE_PRODUCT_MERGED, 10).size());

        service.onProductDeleted(canonico.getId());
        assertNull(state(canonico));
        assertTrue(candidateRepo.findByProductIdOrderByRankAscIdAsc(canonico.getId()).isEmpty());
        assertTrue(aliasRepo.findBySourceProductId(canonico.getId()).isEmpty(), "los alias se conservan sin producto");
        assertEquals(1, eventRepo.recent(canonico.getId(), NsoEvent.TYPE_PRODUCT_DELETED, 10).size());
        productRepo.deleteById(canonico.getId());
        productRepo.deleteById(duplicado.getId());
    }

    // ============================ 9. gate ============================

    @Test
    @Order(9)
    void gateFiltraYBloqueaSoloCuandoEstaActivo() {
        ensureCatalog();
        Product orchid = velmora("Misty Orchid 3.4 Oz Edp Women");
        Product sinNso = product("Marca Fantasma", "Otra Cosa");
        Product otro = product("Marca Fantasma", "Tercera Cosa");
        Product storm = velmora("Silver Storm 1.7 Oz Edp Men");
        rematch(orchid, sinNso, otro, storm);
        assertEquals(ProductNso.STATUS_CON_NSO, status(orchid));
        assertEquals(ProductNso.STATUS_SIN_NSO, status(sinNso));

        // gate apagado: todo igual que antes
        assertFalse(gate.isActive());
        assertTrue(gate.isPublic(sinNso));
        assertTrue(gate.isPurchasable(sinNso.getId()));
        List<Product> lista = List.of(orchid, sinNso);
        assertSame(lista, gate.filterPublic(lista));
        gate.assertPurchasable(sinNso);

        try {
            Map<String, Object> s = service.setGate(true);
            assertEquals(true, s.get("gateEnabled"));
            assertEquals(true, s.get("gateEffective"));
            assertTrue(gate.isActive());
            assertTrue(gate.isPublic(orchid));
            assertFalse(gate.isPublic(sinNso));
            assertFalse(gate.isPurchasable(sinNso.getId()));
            assertEquals(List.of(orchid), gate.filterPublic(lista));

            NsoBlockedException ex = assertThrows(NsoBlockedException.class, () -> gate.assertPurchasable(sinNso));
            assertEquals(List.of(sinNso.getId()), ex.getProductIds());
            assertEquals("«Marca Fantasma Otra Cosa» ya no está disponible. Retíralo de tu pedido para continuar.", ex.getMessage());
            assertFalse(ex.getMessage().toUpperCase().contains("NSO"));
            NsoBlockedException ex2 = assertThrows(NsoBlockedException.class,
                    () -> gate.assertPurchasable(List.of(orchid, sinNso, otro)));
            assertEquals(List.of(sinNso.getId(), otro.getId()), ex2.getProductIds());
            assertTrue(ex2.getMessage().contains("ya no están disponibles"));
            gate.assertPurchasable(orchid);

            // No disponible o archivado: nunca publico
            orchid.setAvailable(false);
            assertFalse(gate.isPublic(orchid));
            orchid.setAvailable(true);

            // nso_accept_can_codes=false: el codigo de Colombia deja de contar
            assertTrue(gate.conNsoIds().contains(storm.getId()));
            AppConfig can = configRepo.findByConfigKey(NsoGate.CFG_ACCEPT_CAN).orElseThrow();
            can.setConfigValue("false");
            configRepo.save(can);
            gate.invalidate();
            try {
                assertFalse(gate.conNsoIds().contains(storm.getId()));
                assertTrue(gate.conNsoIds().contains(orchid.getId()));
            } finally {
                can.setConfigValue("true");
                configRepo.save(can);
                gate.invalidate();
            }
            assertEquals(1, eventRepo.recent(null, NsoEvent.TYPE_GATE_ON, 10).size());
        } finally {
            service.setGate(false);
        }
        assertFalse(gate.isActive());
    }

    // ============================ 10. preview + indices ============================

    @Test
    @Order(10)
    @SuppressWarnings("unchecked")
    void previewDeImportEsSoloLecturaYLasVistasTienenLaFormaDelContrato() {
        ensureCatalog();
        rematch(velmora("Desert Wind Edp Men"));
        Supplier supplier = supplierRepo.findAll().get(0);
        long filas = productNsoRepo.count();
        long candidatos = candidateRepo.count();

        List<ParsedRow> rows = List.of(
                row("Velmora", "Moonlight Rose", "VELMORA MOONLIGHT ROSE 100ML EDP WOMEN"),
                row("Marca Fantasma", "Cosa", "MARCA FANTASMA COSA 100ML"),
                row("Velmora", "Golden Dune", "VELMORA GOLDEN DUNE EDP MEN"));
        NsoService.PreviewResult pr = service.matchPreviewRows(supplier, rows);
        assertTrue(pr.isCatalogLoaded());
        assertEquals(3, pr.getRows().size());
        assertEquals(ProductNso.STATUS_CON_NSO, pr.getRows().get(0).getStatus());
        assertEquals("NSOC90001-25PE", pr.getRows().get(0).getNsoCode());
        assertEquals("AGUA DE PERFUME-MOONLIGHT ROSE", pr.getRows().get(0).getDeclaredName());
        assertEquals("PE", pr.getRows().get(0).getCountry());
        assertEquals(ProductNso.STATUS_SIN_NSO, pr.getRows().get(1).getStatus());
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, pr.getRows().get(2).getStatus());
        assertEquals("IMPORTADORA DEMO SAC", pr.getRows().get(2).getTitular());
        assertEquals(1, pr.getConNso());
        assertEquals(1, pr.getNone());
        assertEquals(1, pr.getBrandOnly());
        assertEquals(filas, productNsoRepo.count(), "el preview no escribe");
        assertEquals(candidatos, candidateRepo.count());

        List<Map<String, Object>> index = service.index();
        assertFalse(index.isEmpty());
        Map<String, Object> co = index.stream().filter(m -> "NSOC90004-24CO".equals(m.get("nsoCode"))).findFirst().orElseThrow();
        assertEquals("CO", co.get("country"));
        assertEquals(true, co.get("otherCanCountry"));
        assertEquals(2024, co.get("nsoYear"));
        assertEquals(false, co.get("possiblyExpired"));

        List<Map<String, Object>> groups = service.brandGroups();
        Map<String, Object> velmora = groups.stream().filter(g -> "velmora".equals(g.get("brandKey"))).findFirst().orElseThrow();
        assertEquals("VELMORA", velmora.get("brandName"));
        assertEquals(1, velmora.get("genericRecords"));
        assertEquals(1, velmora.get("unknownTitularCodes"));
        assertFalse(((List<?>) velmora.get("titulares")).isEmpty());

        List<Map<String, Object>> sinNso = service.productsByStatus(ProductNso.STATUS_SIN_NSO);
        assertFalse(sinNso.isEmpty());
        assertTrue(sinNso.get(0).containsKey("suggestedBrand"));
        List<Map<String, Object>> con = service.productsByStatus(ProductNso.STATUS_CON_NSO);
        Map<String, Object> moon = con.stream().filter(m -> "NSOC90001-25PE".equals(m.get("nsoCode"))).findFirst().orElseThrow();
        assertEquals("AGUA DE PERFUME-MOONLIGHT ROSE", moon.get("declaredName"));
        assertEquals("IMPORTADORA DEMO SAC", moon.get("titular"));
        assertThrows(IllegalArgumentException.class, () -> service.productsByStatus("OTRO"));

        Map<String, Object> page = service.catalog("moonlight", null, 0, 50);
        assertEquals(1L, page.get("total"));
        assertEquals(1, ((List<?>) page.get("items")).size());
    }

    private ParsedRow row(String brand, String name, String rawTitle) {
        ParsedRow r = new ParsedRow();
        r.brand = brand;
        r.name = name;
        r.rawTitle = rawTitle;
        r.ml = 100;
        r.forma = "single";
        r.supplierSku = "SKU-" + name.replace(' ', '-');
        return r;
    }

    // ============================ 11. sin N+1 ============================

    @Test
    @Order(11)
    void rematchSincronoLeeEnBloqueSinConsultasPorProducto() {
        ensureCatalog();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 45; i++) ids.add(velmora("Moonlight Rose Lote " + i + " Edp Women").getId());
        NsoRepositoriesTest.SqlSpy.start();
        List<String> sql;
        try {
            NsoService.RematchOutcome o = service.rematchProducts(ids);
            assertEquals(45, o.getProcessed());
        } finally {
            sql = NsoRepositoriesTest.SqlSpy.stop();
        }
        long selects = sql.stream().filter(s -> s.startsWith("select")).count();
        assertTrue(selects < 40, "las lecturas no deben crecer con cada producto: " + selects);
    }

    // ============================ 12. celdas largas no tumban la carga ============================

    @Test
    @Order(12)
    void celdasMasLargasQueLaColumnaNoRevierteLaCargaEntera() {
        ensureCatalog();
        long antes = recordRepo.count();
        // Mismo archivo que el test del parser: RUC de texto libre (25), categoria/origen/titular largos, y dos filas
        // con marca/nombre imposibles. Antes: un "value too long" revertia TODAS las filas y respondia 500.
        Map<String, Object> r = service.uploadCatalog("NSO-largos.csv", NsoCatalogParserTest.csvConCeldasLargas());
        assertTrue(service.awaitRematchIdle(60_000));
        assertEquals(2, ((Number) r.get("recordsRead")).intValue(), r.toString());
        assertEquals(2, ((List<?>) r.get("invalidRows")).size(), r.toString());
        assertEquals(antes + 2, recordRepo.count(), "las filas buenas SI se guardaron");
        NsoRecord moon = recordRepo.findById("NSOC91101-25PE").orElseThrow();
        assertEquals("20601234567", moon.getRuc());
        assertEquals(80, moon.getOrigen().length());
        // Se restaura la lista de siempre (la carga marco los demas codigos como "ya no vienen").
        upload(CSV);
    }
}
