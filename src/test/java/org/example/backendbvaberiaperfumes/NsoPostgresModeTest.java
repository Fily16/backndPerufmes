package org.example.backendbvaberiaperfumes;

import jakarta.persistence.EntityManagerFactory;
import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
import org.example.backendbvaberiaperfumes.service.ProductMergeService;
import org.example.backendbvaberiaperfumes.service.ProductService;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.example.backendbvaberiaperfumes.service.nso.NsoService;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NSO contra H2 en MODO POSTGRESQL (lo mas parecido a Aiven sin levantar un Postgres): identificadores en minuscula,
 * NULLs al final como en Postgres y la sintaxis/comparaciones del modo PG.
 *
 *  1. Ejercita TODAS las consultas nuevas de los repositorios NSO (y las dos de SupplierOfferRepository) y un flujo
 *     completo: subir la lista, rematch, aprobar/ninguno/asignar/quitar, desactivar codigo, alias de marca, opciones,
 *     fusion y borrado, gate, asignacion de compra con nsoBlocked y preview/commit de un import.
 *  2. Medicion de rendimiento (catalogo del tamano real: ~1,300 perfumes con ofertas y ~1,700 codigos; import de
 *     ~900 filas): tiempos y cantidad de sentencias SQL (Hibernate statistics + inspector por hilo). Los tiempos solo
 *     se imprimen (no se afirman: dependen de la maquina); la cantidad de sentencias SI se afirma, para que un N+1
 *     (una consulta por perfume) se note.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsopg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        "spring.mail.password=",
        "resend.api.key=",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "org.example.backendbvaberiaperfumes.NsoRepositoriesTest$SqlSpy"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NsoPostgresModeTest {

    static final String FOREST_EAN = ean13("779123450001");

    static final String CSV = String.join("\n",
            "nso,categoria,marca,producto,ean,titular,ruc,origen,ultima",
            "NSOC93001-25PE,Arabe,ORVANTA,AGUA DE PERFUME-MOON DUST,,IMPORTADORA ORVANTA SAC,20123456789,France,15/07/2026",
            "NSOC93002-25PE,Arabe,ORVANTA,AGUA DE PERFUME-GOLD REED,,IMPORTADORA ORVANTA SAC,20123456789,,",
            "NSOC93003-24CO,Arabe,ORVANTA,AGUA DE TOCADOR-SEA SALT,,,,,",
            "NSOC93004-25PE,Arabe,ORVANTA,AGUA DE PERFUME,,IMPORTADORA ORVANTA SAC,20123456789,,",
            "NSOC93005-25PE,Arabe,ORVANTA,AGUA DE PERFUME-QUIET FOREST," + FOREST_EAN + ",IMPORTADORA ORVANTA SAC,20123456789,,",
            "NSOC93006-25PE,Arabe,ORVANTA,AGUA DE PERFUME-VELVET ASH,,IMPORTADORA ORVANTA SAC,20123456789,,",
            "NSOC93007-25PE,Arabe,ORVANTA,AGUA DE PERFUME-COPPER RAIN,,IMPORTADORA ORVANTA SAC,20123456789,,") + "\n";

    @Autowired NsoService nso;
    @Autowired NsoGate gate;
    @Autowired AllocationService allocationService;
    @Autowired ExcelImportService importService;
    @Autowired ProductMergeService mergeService;
    @Autowired ProductService productService;
    @Autowired ProductRepository productRepo;
    @Autowired ProductNsoRepository productNsoRepo;
    @Autowired NsoRecordRepository recordRepo;
    @Autowired NsoCandidateRepository candidateRepo;
    @Autowired NsoAliasRepository aliasRepo;
    @Autowired NsoEventRepository eventRepo;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired EntityManagerFactory emf;

    // ============================ helpers ============================

    /** EAN-13 con digito verificador GS1 a partir de 12 digitos. */
    static String ean13(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = twelve.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        return twelve + ((10 - sum % 10) % 10);
    }

    static String gtin14(String ean13) {
        String g = GtinCanonicalizer.canonicalize(ean13).canonical14;
        assertNotNull(g, "GTIN invalido en el test: " + ean13);
        return g;
    }

    private Supplier zimaxx() {
        return supplierRepo.findByName("Zimaxx").orElseThrow();
    }

    private Product product(String brand, String name, String gtin) {
        Product p = new Product();
        p.setSku("NSO-PG-" + UUID.randomUUID());
        p.setBrand(brand);
        p.setName(name);
        p.setMl(100);
        p.setWeightG(600);
        p.setAvailable(true);
        p.setArchived(false);
        p.setWholesalePricePen(150.0);
        p.setPriceUsd(20.0);
        p.setGtin(gtin);
        p = productRepo.save(p);
        SupplierOffer o = new SupplierOffer();
        o.setProduct(p);
        o.setSupplier(zimaxx());
        o.setOfferKey("NSO-PG-" + p.getId());
        o.setCostUsd(20.0);
        o.setInStock(true);
        o.setSupplierSku("ZX-PG-" + p.getId());
        o.setRawTitle(brand.toUpperCase(Locale.ROOT) + " " + name.toUpperCase(Locale.ROOT));
        o.setGtin(gtin);
        offerRepo.save(o);
        return p;
    }

    private String status(Product p) {
        return productNsoRepo.findById(p.getId()).map(ProductNso::getStatus).orElse(ProductNso.STATUS_SIN_VERIFICAR);
    }

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    /** Mide una operacion: tiempo, sentencias de ESTE hilo (inspector) y de Hibernate statistics. */
    private record Measure(String what, long ms, List<String> sql, long prepared, long queries) {
        long count(String prefix) {
            return sql.stream().filter(s -> s.startsWith(prefix)).count();
        }

        long count(String prefix, String table) {
            return sql.stream().filter(s -> s.startsWith(prefix) && s.contains(table)).count();
        }

        /** Las n sentencias mas repetidas (para ver de donde sale un N+1). */
        String top(int n) {
            Map<String, Long> c = new HashMap<>();
            for (String q : sql) c.merge(q.length() > 160 ? q.substring(0, 160) : q, 1L, Long::sum);
            return c.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(n)
                    .map(e -> e.getValue() + "x " + e.getKey()).toList().toString();
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s: %d ms | sentencias hilo=%d (select=%d insert=%d update=%d delete=%d) "
                            + "| hibernate prepared=%d queries=%d", what, ms, sql.size(), count("select"),
                    count("insert"), count("update"), count("delete"), prepared, queries);
        }
    }

    private <T> Measure measure(String what, java.util.function.Supplier<T> action, List<T> out) {
        Statistics st = stats();
        st.clear();
        NsoRepositoriesTest.SqlSpy.start();
        long t0 = System.nanoTime();
        List<String> sql;
        try {
            T r = action.get();
            if (out != null) out.add(r);
        } finally {
            sql = NsoRepositoriesTest.SqlSpy.stop();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Measure m = new Measure(what, ms, sql, st.getPrepareStatementCount(), st.getQueryExecutionCount());
        System.out.println("[NSO-PERF] " + m);
        System.out.println("[NSO-PERF]    mas repetidas: " + m.top(4));
        return m;
    }

    // ============================ 1. todas las consultas + flujo completo ============================

    @Test
    @Order(1)
    void todasLasConsultasNsoYUnFlujoCompletoEnModoPostgres() {
        // --- productos y lista ---
        Product moon = product("Orvanta", "Moon Dust 3.4 Oz Edp Women", null);
        Product reed = product("Orvanta", "Gold Reed Intense 3.4 Oz Edp Men", null);
        Product salt = product("Orvanta", "Sea Salt 3.4 Oz Edt Men", null);
        Product leaf = product("Orvanta", "Paper Leaf 3.4 Oz Edp Women", null);
        Product forest = product("Orvanta", "Forest Hush Edp", gtin14(FOREST_EAN));
        Product ash = product("Orvanta", "Velvet Ash 3.4 Oz Edp Unisex", null);
        Product ashDup = product("Orvanta", "Velvet Ash Edp Unisex 100ml", null);
        Product rain = product("Orvanta", "Copper Rain 3.4 Oz Edp Women", null);
        Product nad = product("Nadiera", "Night Lotus 3.4 Oz Edp Women", null);
        Product del = product("Orvanta", "Borrar Este 3.4 Oz Edp Women", null);
        // (borrar un perfume con ofertas no se permite por FK: se le quitan antes, como hace el panel)
        offerRepo.deleteAll(offerRepo.findByProduct_Id(del.getId()));

        Map<String, Object> up = nso.uploadCatalog("nso.csv", CSV.getBytes(StandardCharsets.UTF_8));
        assertEquals(7, ((Number) up.get("recordsRead")).intValue(), up.toString());
        assertTrue(nso.awaitRematchIdle(60_000));
        gate.invalidate();
        assertEquals(ProductNso.STATUS_CON_NSO, status(moon));
        assertEquals(ProductNso.STATUS_EN_REVISION, status(reed));
        assertEquals(ProductNso.STATUS_CON_NSO, status(salt));
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, status(leaf));
        assertEquals(ProductNso.STATUS_CON_NSO, status(forest));
        assertEquals(ProductNso.MATCHED_UPC, productNsoRepo.findById(forest.getId()).orElseThrow().getMatchedBy());
        assertEquals(ProductNso.STATUS_SIN_NSO, status(nad));

        // --- NsoRecordRepository ---
        assertEquals(7, recordRepo.countByActiveTrue());
        assertEquals(7, recordRepo.findByActiveTrue().size());
        assertEquals(7, recordRepo.findByBrandKey("orvanta").size());
        assertEquals(7, recordRepo.findByBrandKeyAndActiveTrue("orvanta").size());
        assertEquals(2, recordRepo.findByCodeIn(List.of("NSOC93001-25PE", "NSOC93003-24CO", "NSOC00000-25PE")).size());
        assertEquals("NSOC93005-25PE", recordRepo.findByEan(gtin14(FOREST_EAN)).get(0).getCode());
        assertTrue(recordRepo.findByInLastUploadFalseOrderByBrandKeyAscCodeAsc().isEmpty());
        assertEquals(List.of("orvanta"), recordRepo.findActiveBrandKeys());
        assertEquals(7, recordRepo.searchCatalog(null, null, 0, 50).getTotalElements());
        assertEquals(1, recordRepo.searchCatalog("moon", "", 0, 50).getTotalElements());
        assertEquals(6, recordRepo.searchCatalog("20123456789", "Orvanta", 0, 50).getTotalElements(), "busca por RUC");
        assertEquals(2, recordRepo.searchCatalog("", "orvanta", 1, 5).getContent().size(), "segunda pagina");
        assertEquals(7, recordRepo.search("", "", PageRequest.of(0, 10)).getTotalElements());

        // --- ProductNsoRepository ---
        Set<Long> eligible = new HashSet<>(productNsoRepo.findEligibleProductIds());
        assertTrue(eligible.containsAll(List.of(moon.getId(), salt.getId(), forest.getId())));
        Set<Long> peru = new HashSet<>(productNsoRepo.findEligiblePeruProductIds());
        assertTrue(peru.contains(moon.getId()));
        assertFalse(peru.contains(salt.getId()), "codigo CO fuera en solo-Peru");
        // Un perfume creado despues de la verificacion no tiene fila: cuenta como SIN_VERIFICAR.
        Product pending = product("Orvanta", "Aun Sin Verificar Edp", null);
        Map<String, Long> byStatus = new HashMap<>();
        for (Object[] row : productNsoRepo.countCurrentProductsByStatus()) byStatus.put((String) row[0], ((Number) row[1]).longValue());
        assertEquals(1L, byStatus.get(ProductNso.STATUS_SIN_VERIFICAR), byStatus.toString());
        assertEquals(productRepo.countByArchivedFalse(), byStatus.values().stream().mapToLong(Long::longValue).sum());
        assertTrue(productNsoRepo.findCurrentProductsWithoutState().stream().anyMatch(x -> x.getId().equals(pending.getId())));
        assertTrue(productNsoRepo.countPublicNow() >= 10);
        assertTrue(productNsoRepo.countPublicIfActivated() >= 3);
        assertEquals(productNsoRepo.countPublicIfActivated() - 1, productNsoRepo.countPublicIfActivatedPeru());
        assertTrue(productNsoRepo.findCurrentByStatus(ProductNso.STATUS_CON_NSO).size() >= 3);
        assertTrue(productNsoRepo.findProductIdsByBrandKeyIn(List.of("orvanta", "nadiera")).contains(moon.getId()));
        assertTrue(productNsoRepo.countByStatus(ProductNso.STATUS_CON_NSO) >= 3);
        assertFalse(productNsoRepo.findByStatus(ProductNso.STATUS_EN_REVISION).isEmpty());
        assertEquals(2, productNsoRepo.findByProductIdIn(List.of(moon.getId(), reed.getId())).size());
        assertEquals(1, productNsoRepo.findByNsoCodeAndStatus("NSOC93001-25PE", ProductNso.STATUS_CON_NSO).size());
        assertEquals(1, productNsoRepo.findByNsoCode("NSOC93001-25PE").size());
        assertTrue(productNsoRepo.findByBrandKeyAndLockedFalse("orvanta").size() >= 8);
        assertTrue(productNsoRepo.findByLockedTrue().isEmpty());
        assertEquals(ProductNso.STATUSES, List.copyOf(productNsoRepo.countByStatusMap().keySet()));
        assertTrue(productNsoRepo.findConNsoProductIds().contains(moon.getId()));
        assertFalse(productNsoRepo.findConNsoPeruProductIds().contains(salt.getId()));
        assertEquals(1L, productNsoRepo.countLinkedByCode().get("NSOC93001-25PE"));

        // --- NsoCandidateRepository ---
        NsoCandidate reedCand = candidateRepo.findByProductIdAndStatusOrderByRankAscIdAsc(reed.getId(), NsoCandidate.STATUS_PENDING).get(0);
        assertEquals("NSOC93002-25PE", reedCand.getNsoCode());
        assertTrue(candidateRepo.countCurrentProductsWithPending() >= 1);
        assertTrue(candidateRepo.existsByProductIdAndNsoCode(reed.getId(), "NSOC93002-25PE"));
        assertTrue(candidateRepo.findByProductIdAndNsoCode(reed.getId(), "NSOC93002-25PE").isPresent());
        assertFalse(candidateRepo.findByProductIdOrderByRankAscIdAsc(reed.getId()).isEmpty());
        assertFalse(candidateRepo.findByProductIdIn(List.of(reed.getId(), moon.getId())).isEmpty());
        assertFalse(candidateRepo.findByProductIdInAndStatus(List.of(reed.getId()), NsoCandidate.STATUS_PENDING).isEmpty());
        assertFalse(candidateRepo.findByStatusOrderByProductIdAscRankAscIdAsc(NsoCandidate.STATUS_PENDING).isEmpty());
        assertFalse(candidateRepo.findByNsoCodeAndStatus("NSOC93002-25PE", NsoCandidate.STATUS_PENDING).isEmpty());
        assertTrue(candidateRepo.countProductsWithPending() >= 1);
        assertTrue(candidateRepo.findProductIdsByStatus(NsoCandidate.STATUS_PENDING).contains(reed.getId()));
        NsoCandidate tmp = candidateRepo.save(new NsoCandidate(990001L, "NSOC93001-25PE", 0.7, 1, NsoCandidate.ORIGIN_MATCHER));
        assertEquals(1, candidateRepo.supersedePending(990001L, LocalDateTime.now()));
        assertEquals(1, candidateRepo.deleteByProductId(990001L));
        assertFalse(candidateRepo.existsById(tmp.getId()));

        // --- decisiones de la admin (alias, eventos) ---
        Map<String, Object> acc = nso.accept(reedCand.getId());
        assertEquals("CON_NSO", acc.get("status"));
        assertTrue(productNsoRepo.findById(reed.getId()).orElseThrow().getLocked());
        nso.rejectAll(leaf.getId());
        assertEquals(ProductNso.STATUS_CON_NSO, nso.assignManual(nad.getId(), new NsoService.ManualCode("NSOC93007-25PE")).get("status"));
        assertNotEquals(ProductNso.STATUS_CON_NSO, nso.unassign(nad.getId()).get("status"));
        Map<String, Object> preview = nso.setCodeActive("NSOC93001-25PE", false, false);
        assertEquals(false, preview.get("applied"));
        assertEquals(true, nso.setCodeActive("NSOC93001-25PE", false, true).get("applied"));
        assertNotEquals(ProductNso.STATUS_CON_NSO, status(moon));
        assertEquals(true, nso.setCodeActive("NSOC93001-25PE", true, true).get("applied"));
        assertEquals(ProductNso.STATUS_CON_NSO, status(moon));
        assertEquals(true, nso.addBrandAlias("Nadiera", "Orvanta").get("ok"));

        // --- NsoAliasRepository ---
        assertFalse(aliasRepo.findByKind(NsoAlias.KIND_NAME_KEY).isEmpty());
        assertFalse(aliasRepo.findByKindAndPolarity(NsoAlias.KIND_NAME_KEY, NsoAlias.POSITIVE).isEmpty());
        assertFalse(aliasRepo.findByNsoCode("NSOC93002-25PE").isEmpty());
        assertFalse(aliasRepo.findBySourceProductId(reed.getId()).isEmpty());
        assertFalse(aliasRepo.findByOrigin(NsoAlias.ORIGIN_APROBADO).isEmpty());
        NsoAlias sku = aliasRepo.findByKind(NsoAlias.KIND_SUPPLIER_SKU).get(0);
        assertEquals(1, aliasRepo.findByKindAndAliasKey(sku.getKind(), sku.getAliasKey()).stream()
                .filter(a -> Objects.equals(a.getNsoCode(), sku.getNsoCode())).count());
        assertFalse(aliasRepo.findByKindAndAliasKeyIn(sku.getKind(), List.of(sku.getAliasKey(), "x|y")).isEmpty());
        assertTrue(aliasRepo.findUnique(sku.getKind(), sku.getAliasKey(), sku.getNsoCode()).isPresent());
        assertTrue(aliasRepo.findBrandAlias("nadiera").isPresent());
        aliasRepo.save(new NsoAlias(NsoAlias.KIND_GTIN, "00000000000017", "NSOC93001-25PE", NsoAlias.POSITIVE, NsoAlias.ORIGIN_RESEARCH));
        assertEquals(1, aliasRepo.deleteByOrigin(NsoAlias.ORIGIN_RESEARCH));

        // --- opciones (PUT /settings) ---
        Map<String, Object> soloPeru = nso.updateSettings(false, 0.7);
        assertEquals(false, soloPeru.get("acceptCanCodes"));
        assertTrue(nso.awaitRematchIdle(60_000));
        assertNotEquals(ProductNso.STATUS_CON_NSO, status(salt));
        nso.updateSettings(true, 0.66);
        assertTrue(nso.awaitRematchIdle(60_000));
        assertEquals(ProductNso.STATUS_CON_NSO, status(salt));

        // --- fusion y borrado (ganchos tras el commit) ---
        mergeService.merge(ash.getId(), ashDup.getId());
        assertTrue(productNsoRepo.findById(ashDup.getId()).isEmpty(), "la fila del duplicado se limpia");
        productService.delete(del.getId());
        assertTrue(productNsoRepo.findById(del.getId()).isEmpty());

        // --- NsoEventRepository ---
        assertFalse(eventRepo.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 5)).isEmpty());
        assertFalse(eventRepo.findByProductIdOrderByCreatedAtDescIdDesc(reed.getId(), PageRequest.of(0, 5)).isEmpty());
        assertFalse(eventRepo.findByTypeOrderByCreatedAtDescIdDesc(NsoEvent.TYPE_SETTINGS, PageRequest.of(0, 5)).isEmpty());
        assertFalse(eventRepo.findByProductIdAndTypeOrderByCreatedAtDescIdDesc(reed.getId(), NsoEvent.TYPE_CANDIDATE_ACCEPTED,
                PageRequest.of(0, 5)).isEmpty());
        assertTrue(eventRepo.findFirstByTypeOrderByCreatedAtDescIdDesc(NsoEvent.TYPE_CATALOG_UPLOAD).isPresent());
        assertFalse(eventRepo.recent(null, null, 10).isEmpty());

        // --- SupplierOfferRepository (consultas NSO) ---
        assertTrue(offerRepo.findAllForNsoMatching().size() >= 10);
        assertEquals(2, offerRepo.findForNsoByProductIds(List.of(moon.getId(), reed.getId())).size());

        // --- lecturas del panel ---
        Map<String, Object> summary = nso.summary();
        assertTrue(summary.containsKey("reviewMinScore"));
        assertFalse(nso.index().isEmpty());
        nso.review();
        nso.brandGroups();
        for (String s : ProductNso.STATUSES) nso.productsByStatus(s);
        assertEquals(7L, ((Number) nso.catalog("", "orvanta", 0, 50).get("total")).longValue());
        nso.events(reed.getId(), null, 50);
        nso.pendingCount();

        // --- gate + asignacion de compra con nsoBlocked ---
        nso.setGate(true);
        try {
            assertTrue(gate.isActive());
            assertTrue(gate.isPurchasable(moon.getId()));
            assertFalse(gate.isPurchasable(leaf.getId()));
            Consolidado con = new Consolidado();
            con.setStatus("ABIERTO");
            con = consolidadoRepo.save(con);
            org.example.backendbvaberiaperfumes.model.Order o = new org.example.backendbvaberiaperfumes.model.Order();
            o.setConsolidado(con);
            o.setClientName("Cliente PG");
            o.setClientPhone("955000111");
            o.setPaymentStatus("SEPARADO");
            o.getItems().add(orderItem(o, moon, 1));
            o.getItems().add(orderItem(o, leaf, 2));
            orderRepo.save(o);
            AllocationResponse alloc = allocationService.computeAllocation(con.getId());
            assertEquals(1, alloc.nsoBlocked.size(), "solo el perfume sin NSO sale aparte");
            assertEquals(leaf.getId(), alloc.nsoBlocked.get(0).productId);
            assertEquals(2, alloc.nsoBlocked.get(0).quantity);

            // --- preview + commit de un import ---
            Supplier s = supplierRepo.save(new Supplier("PgProveedor", 0.0, false));
            ExcelImportService.ParsedData pd = new ExcelImportService.ParsedData();
            pd.rows.add(row("Orvanta", "Moon Dust", "ORVANTA MOON DUST 100ML EDP WOMEN", null, "PG-1"));
            pd.rows.add(row("Orvanta", "Mist Garden", "ORVANTA MIST GARDEN 100ML EDP WOMEN", null, "PG-2"));
            pd.rows.add(row("Zelqora", "Blue Stone", "ZELQORA BLUE STONE 100ML EDP MEN", null, "PG-3"));
            ImportPreview p = importService.buildPreview(s, pd);
            assertTrue(p.nsoCatalogLoaded);
            assertTrue(p.nsoGateEnabled);
            assertEquals(3, p.rows.size());
            assertEquals(ProductNso.STATUS_CON_NSO, p.rows.get(0).nsoStatus);
            assertEquals(1, p.nsoConNso);
            ImportSummary sum = importService.commit(s, pd.rows);
            assertEquals(1, sum.getNsoConNso());
            assertEquals(3, sum.getNsoConNso() + sum.getNsoReview() + sum.getNsoBrandOnly() + sum.getNsoNone());
        } finally {
            nso.setGate(false);
        }
        assertFalse(gate.isActive());
    }

    private static OrderItem orderItem(org.example.backendbvaberiaperfumes.model.Order o, Product p, int qty) {
        OrderItem it = new OrderItem();
        it.setOrder(o);
        it.setProduct(p);
        it.setQuantity(qty);
        it.setUnitPricePen(150.0);
        it.calculateSubtotal();
        return it;
    }

    private static ParsedRow row(String brand, String name, String rawTitle, String gtin, String sku) {
        ParsedRow r = new ParsedRow();
        r.brand = brand;
        r.name = name;
        r.rawTitle = rawTitle;
        r.ml = 100;
        r.forma = "single";
        r.costUsd = 22.0;
        r.inStock = true;
        r.supplierSku = sku;
        r.gtin = gtin;
        r.gtinStatus = gtin == null ? "EMPTY" : "OK";
        return r;
    }

    // ============================ 2. rendimiento con el tamano real ============================

    static final String[] SYLLABLES = {"ka", "lo", "mi", "ra", "ve", "zo", "ni", "ta", "su", "de", "fo", "gu"};
    static final String[] WORDS = {"amber", "cedar", "velvet", "silver", "golden", "ocean", "saffron", "royal", "musk",
            "rose", "iris", "lotus", "ember", "frost", "moss", "pearl", "stone", "cloud", "storm", "honey", "spice",
            "citrus", "jasmine", "orchid", "leather", "tobacco", "vanilla", "coral", "dune", "breeze"};

    @Test
    @Order(2)
    void rendimientoConCatalogoDelTamanoReal() {
        final int brands = 60, perBrand = 22, withoutNso = 5;
        Supplier zx = zimaxx();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        List<String> brandNames = new ArrayList<>();
        for (int i = 0; brandNames.size() < brands; i++) {
            String b = SYLLABLES[i % 12] + SYLLABLES[(i / 12 + 3) % 12] + SYLLABLES[(i * 7 + 5) % 12] + "x";
            b = Character.toUpperCase(b.charAt(0)) + b.substring(1);
            if (!brandNames.contains(b)) brandNames.add(b);
        }

        // --- ~1,320 perfumes con ofertas (1 en Zimaxx; 1 de cada 3 tambien en Magnet) ---
        List<Product> products = new ArrayList<>();
        List<String> productGtins = new ArrayList<>();
        StringBuilder csv = new StringBuilder("nso,categoria,marca,producto,ean,titular,ruc\n");
        int code = 10000, seq = 0;
        for (int b = 0; b < brands; b++) {
            String brand = brandNames.get(b);
            boolean brandHasNso = b >= withoutNso;
            List<String> names = new ArrayList<>();
            for (int k = 0; names.size() < perBrand; k++) {
                String n = cap(WORDS[(k + b) % WORDS.length]) + " " + cap(WORDS[(k * 7 + b * 3 + 1) % WORDS.length]);
                if (!names.contains(n) && !n.split(" ")[0].equals(n.split(" ")[1])) names.add(n);
            }
            for (int k = 0; k < perBrand; k++) {
                String gender = k % 3 == 0 ? "Men" : "Women";
                String title = names.get(k) + " 3.4 Oz Edp " + gender;
                String g = gtin14(ean13(String.format(Locale.ROOT, "78%010d", ++seq)));
                Product p = new Product();
                p.setSku("NSO-PERF-" + seq);
                p.setBrand(brand);
                p.setName(names.get(k) + " Edp " + gender);
                p.setMl(100);
                p.setWeightG(600);
                p.setAvailable(true);
                p.setArchived(false);
                p.setWholesalePricePen(150.0);
                p.setPriceUsd(20.0);
                p.setGtin(g);
                products.add(p);
                productGtins.add(g);
                if (brandHasNso) {
                    // 60% exacto (automatico), 15% variante "INTENSE" (revision), el resto sin su perfume en la lista.
                    if (k % 20 < 12) csv.append(csvRow(++code, brand, "AGUA DE PERFUME-" + names.get(k).toUpperCase(Locale.ROOT)
                            + (gender.equals("Men") ? " POUR HOMME" : " POUR FEMME")));
                    else if (k % 20 < 15) csv.append(csvRow(++code, brand, "AGUA DE PERFUME-" + names.get(k).toUpperCase(Locale.ROOT) + " INTENSE"));
                }
            }
        }
        productRepo.saveAll(products);
        List<SupplierOffer> offers = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            offers.add(offer(p, zx, "ZX-PERF-" + i, productGtins.get(i)));
            if (i % 3 == 0) offers.add(offer(p, magnet, "MG-PERF-" + i, productGtins.get(i)));
        }
        offerRepo.saveAll(offers);
        // Codigos de relleno hasta ~1,700 (otros perfumes de las mismas marcas + genericos de aduanas).
        int b = withoutNso;
        while (code - 10000 < 1_700) {
            String brand = brandNames.get(b);
            csv.append(csvRow(++code, brand, (code % 9 == 0) ? "AGUA DE PERFUME"
                    : "EXTRACTO DE PERFUME-" + WORDS[code % WORDS.length].toUpperCase(Locale.ROOT) + " "
                    + WORDS[(code / 3) % WORDS.length].toUpperCase(Locale.ROOT) + " " + (code % 50)));
            b = b + 1 < brands ? b + 1 : withoutNso;
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        System.out.println("[NSO-PERF] catalogo: " + products.size() + " perfumes, " + offers.size() + " ofertas, "
                + (code - 10000) + " codigos NSO");

        // --- carga de la lista + verificacion completa en segundo plano ---
        long t0 = System.nanoTime();
        Map<String, Object> up = nso.uploadCatalog("nso-perf.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(nso.awaitRematchIdle(300_000));
        long uploadMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[NSO-PERF] subir lista (" + up.get("recordsRead") + " codigos) + verificar todo en segundo plano: "
                + uploadMs + " ms");
        Map<String, Integer> counts = new TreeMap<>();
        for (ProductNso st : productNsoRepo.findByProductIdIn(ids)) counts.merge(st.getStatus(), 1, Integer::sum);
        System.out.println("[NSO-PERF] estados: " + counts);
        assertTrue(counts.getOrDefault(ProductNso.STATUS_CON_NSO, 0) > 500, counts.toString());
        assertTrue(counts.getOrDefault(ProductNso.STATUS_EN_REVISION, 0) > 100, counts.toString());

        // --- rematchProducts sincrono sobre TODOS ---
        List<NsoService.RematchOutcome> outs = new ArrayList<>();
        Measure rematch = measure("rematchProducts(" + ids.size() + ")", () -> nso.rematchProducts(ids), outs);
        assertEquals(ids.size(), outs.get(0).getProcessed());
        // Lecturas en bloque: nunca una consulta por perfume (N+1). Con 1,320 perfumes y bloques de 500 son decenas.
        assertTrue(rematch.count("select") < 60, "rematchProducts no debe consultar por perfume: " + rematch);
        assertEquals(0, rematch.count("select", "from products p1_0 where p1_0.id=?"), rematch.toString());

        // --- import de ~900 filas: 700 perfumes ya existentes (UPC) + 200 nuevos sin UPC ---
        Supplier imp = supplierRepo.save(new Supplier("PerfProveedor", 0.0, false));
        ExcelImportService.ParsedData pd = new ExcelImportService.ParsedData();
        for (int i = 0; i < 700; i++) {
            Product p = products.get(i);
            pd.rows.add(row(p.getBrand(), p.getName(), p.getBrand().toUpperCase(Locale.ROOT) + " "
                    + p.getName().toUpperCase(Locale.ROOT) + " 100ML", productGtins.get(i), "PF-" + i));
        }
        for (int i = 0; i < 200; i++) {
            String brand = brandNames.get(withoutNso + (i % (brands - withoutNso)));
            String name = "Nuevo " + cap(WORDS[i % WORDS.length]) + " " + (i + 1) + " Edp Women";
            pd.rows.add(row(brand, name, brand.toUpperCase(Locale.ROOT) + " " + name.toUpperCase(Locale.ROOT) + " 100ML",
                    null, "PFN-" + i));
        }
        List<ImportPreview> previews = new ArrayList<>();
        Measure preview = measure("preview import " + pd.rows.size() + " filas", () -> importService.buildPreview(imp, pd), previews);
        assertEquals(900, previews.get(0).rows.size());
        assertTrue(previews.get(0).nsoCatalogLoaded);
        assertTrue(previews.get(0).nsoConNso > 300, "veredictos NSO por fila: " + previews.get(0).nsoConNso);
        List<NsoService.PreviewResult> nsoPreview = new ArrayList<>();
        List<Long> pids = new ArrayList<>();
        for (ImportPreview.Line l : previews.get(0).rows) pids.add(l.matchedProductId);
        Measure nsoOnly = measure("  (solo la parte NSO del preview)", () -> nso.matchPreviewRows(imp, pd.rows, pids), nsoPreview);
        assertTrue(nsoOnly.count("select") < 40, "el veredicto NSO del preview lee en bloque: " + nsoOnly);
        // Antes: 6,325 sentencias (cada fila releia 7 claves de app_config para simular su precio: minutos contra Aiven).
        assertTrue(preview.count("select") < 120, "preview sin N+1: " + preview);
        assertTrue(preview.count("select", "app_config") < 30, "la config de precios se lee una vez: " + preview);

        List<ImportSummary> sums = new ArrayList<>();
        Measure commit = measure("commit import " + pd.rows.size() + " filas", () -> importService.commit(imp, pd.rows), sums);
        ImportSummary sum = sums.get(0);
        assertEquals(900, sum.getNsoConNso() + sum.getNsoReview() + sum.getNsoBrandOnly() + sum.getNsoNone(), sum.getNotes().toString());
        assertTrue(commit.count("select", "product_nso") < 20, "product_nso se lee en bloque en el commit: " + commit);
        assertTrue(commit.count("select", "nso_") < 60, "tablas NSO en bloque en el commit: " + commit);
        // Antes: 8,537 sentencias / 7,433 selects (config de precios releida por cada perfume al recalcular su precio).
        assertTrue(commit.count("select", "app_config") < 30, "la config de precios se lee una vez: " + commit);
        assertTrue(commit.count("select") < 400, "commit sin N+1 de lecturas: " + commit);
    }

    private static String cap(String w) {
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    private static String csvRow(int code, String brand, String declared) {
        return "NSOC" + code + "-25PE,Arabe," + brand.toUpperCase(Locale.ROOT) + "," + declared
                + ",,IMPORTADORA " + brand.toUpperCase(Locale.ROOT) + " SAC,20" + String.format(Locale.ROOT, "%09d", code) + "\n";
    }

    private static SupplierOffer offer(Product p, Supplier s, String key, String gtin) {
        SupplierOffer o = new SupplierOffer();
        o.setProduct(p);
        o.setSupplier(s);
        o.setOfferKey(key);
        o.setCostUsd(20.0);
        o.setInStock(true);
        o.setSupplierSku(key);
        o.setRawTitle(p.getBrand().toUpperCase(Locale.ROOT) + " " + p.getName().toUpperCase(Locale.ROOT) + " 3.4 OZ");
        o.setGtin(gtin);
        return o;
    }
}
