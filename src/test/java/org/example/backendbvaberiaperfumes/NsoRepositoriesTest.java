package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.example.backendbvaberiaperfumes.model.NsoCandidate;
import org.example.backendbvaberiaperfumes.model.NsoEvent;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.NsoAliasRepository;
import org.example.backendbvaberiaperfumes.repository.NsoCandidateRepository;
import org.example.backendbvaberiaperfumes.repository.NsoEventRepository;
import org.example.backendbvaberiaperfumes.repository.NsoRecordRepository;
import org.example.backendbvaberiaperfumes.repository.ProductNsoRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tablas NSO: guardar/leer cada entidad, Persistable sin SELECT previo y claves unicas. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsorepositoriestest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        "spring.mail.password=",
        "resend.api.key=",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "org.example.backendbvaberiaperfumes.NsoRepositoriesTest$SqlSpy"
})
class NsoRepositoriesTest {

    /** Captura el SQL que Hibernate ejecuta en ESTE hilo (el scheduler y otros hilos no ensucian la medicion). */
    public static class SqlSpy implements StatementInspector {
        private static final ThreadLocal<List<String>> CAPTURE = new ThreadLocal<>();

        public SqlSpy() {}

        static void start() { CAPTURE.set(new ArrayList<>()); }

        static List<String> stop() {
            List<String> l = CAPTURE.get();
            CAPTURE.remove();
            return l == null ? List.of() : l;
        }

        @Override
        public String inspect(String sql) {
            List<String> l = CAPTURE.get();
            if (l != null) l.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }
    }

    @Autowired NsoRecordRepository recordRepo;
    @Autowired ProductNsoRepository productNsoRepo;
    @Autowired NsoCandidateRepository candidateRepo;
    @Autowired NsoAliasRepository aliasRepo;
    @Autowired NsoEventRepository eventRepo;
    @Autowired AppConfigRepository configRepo;

    private static long count(List<String> sql, String prefix, String table) {
        return sql.stream().filter(s -> s.startsWith(prefix) && s.contains(table)).count();
    }

    @Test
    void registroNuevoSeInsertaSinSelectPrevio() {
        NsoRecord r = new NsoRecord("NSOC99901-25PE", "ANTONIO BANDERAS", "antonio banderas", "KING OF SEDUCTION 100ML EDT");
        r.setTitular("TITULAR DEMO UNO S.A");
        r.setRuc("20999999901");
        r.setNsoYear(2025);
        r.setCountry("PE");
        r.setSource(NsoRecord.SOURCE_CSV);
        assertTrue(r.isNew());

        SqlSpy.start();
        recordRepo.saveAndFlush(r);
        List<String> sql = SqlSpy.stop();

        assertEquals(1, count(sql, "insert", "nso_records"), sql.toString());
        assertEquals(0, count(sql, "select", "nso_records"), "Persistable: sin SELECT previo. SQL: " + sql);
        assertFalse(r.isNew(), "@PostPersist apaga el flag");

        NsoRecord loaded = recordRepo.findById("NSOC99901-25PE").orElseThrow();
        assertFalse(loaded.isNew(), "@PostLoad apaga el flag");
        assertEquals("20999999901", loaded.getRuc());
        assertTrue(loaded.getActive());
        assertTrue(loaded.getInLastUpload());
        assertNotNull(loaded.getCreatedAt());

        loaded.setTitular("OTRO TITULAR");
        recordRepo.saveAndFlush(loaded);
        assertEquals("OTRO TITULAR", recordRepo.findById("NSOC99901-25PE").orElseThrow().getTitular());

        // Un registro NUEVO con un codigo que ya existe no pisa en silencio: persist -> clave duplicada.
        NsoRecord dup = new NsoRecord("NSOC99901-25PE", "X", "x", "Y");
        assertThrows(DataIntegrityViolationException.class, () -> recordRepo.saveAndFlush(dup));
        assertEquals("ANTONIO BANDERAS", recordRepo.findById("NSOC99901-25PE").orElseThrow().getBrand());
    }

    @Test
    void busquedaPaginadaDelCatalogo() {
        NsoRecord a = new NsoRecord("NSOC99911-25PE", "LATTAFA", "lattafa", "AGUA DE PERFUME YARA CANDY");
        a.setTitular("IMPORTADORA ZETA S.A.C.");
        NsoRecord b = new NsoRecord("NSOC99912-24CO", "LATTAFA", "lattafa", "ASAD EDP");
        b.setActive(false);
        NsoRecord c = new NsoRecord("NSOC99913-25PE", "ZZ OTRA MARCA", "zz otra marca", "YARA ZZ");
        recordRepo.saveAll(List.of(a, b, c));

        Page<NsoRecord> byBrand = recordRepo.searchCatalog(null, "Lattafa", 0, 50);
        assertEquals(2, byBrand.getTotalElements());
        assertEquals("NSOC99911-25PE", byBrand.getContent().get(0).getCode(), "orden marca, codigo");

        Page<NsoRecord> byText = recordRepo.searchCatalog("  YARA ", "", 0, 50);
        assertTrue(byText.getContent().stream().anyMatch(r -> r.getCode().equals("NSOC99911-25PE")));
        assertTrue(byText.getContent().stream().anyMatch(r -> r.getCode().equals("NSOC99913-25PE")));

        assertEquals(1, recordRepo.searchCatalog("zeta", "lattafa", 0, 50).getTotalElements(), "busca por titular");
        assertEquals(1, recordRepo.searchCatalog("nsoc99912", null, 0, 50).getTotalElements(), "busca por codigo");
        Page<NsoRecord> paged = recordRepo.searchCatalog("", "lattafa", 1, 1);
        assertEquals(1, paged.getContent().size());
        assertEquals(2, paged.getTotalElements());

        assertTrue(recordRepo.findActiveBrandKeys().contains("lattafa"));
        assertEquals(1, recordRepo.findByBrandKeyAndActiveTrue("lattafa").size());
        assertTrue(recordRepo.countByActiveTrue() >= 2);
    }

    @Test
    void estadoDeProductoPersistableYConteos() {
        ProductNso pn = new ProductNso(900001L, ProductNso.STATUS_CON_NSO);
        pn.setNsoCode("NSOC99921-25PE");
        pn.setMatchedBy(ProductNso.MATCHED_NOMBRE);
        pn.setScore(1.0);
        pn.setBrandKey("afnan");
        pn.setReasonsJson("[\"nombre exacto\"]");
        pn.setCatalogVersion(1);

        SqlSpy.start();
        productNsoRepo.saveAndFlush(pn);
        List<String> sql = SqlSpy.stop();
        assertEquals(1, count(sql, "insert", "product_nso"), sql.toString());
        assertEquals(0, count(sql, "select", "product_nso"), "Persistable: sin SELECT previo. SQL: " + sql);

        ProductNso co = new ProductNso(900002L, ProductNso.STATUS_CON_NSO);
        co.setNsoCode("NSOC99922-25CO");
        productNsoRepo.saveAndFlush(co);
        productNsoRepo.saveAndFlush(new ProductNso(900003L, ProductNso.STATUS_EN_REVISION));

        ProductNso loaded = productNsoRepo.findById(900001L).orElseThrow();
        assertFalse(loaded.isNew());
        assertFalse(loaded.getLocked(), "locked nace en false");
        assertNotNull(loaded.getCheckedAt());

        List<Long> conNso = productNsoRepo.findConNsoProductIds();
        assertTrue(conNso.containsAll(List.of(900001L, 900002L)));
        assertFalse(conNso.contains(900003L));
        List<Long> peru = productNsoRepo.findConNsoPeruProductIds();
        assertTrue(peru.contains(900001L));
        assertFalse(peru.contains(900002L), "codigo CO fuera cuando no se aceptan otros paises");

        Map<String, Long> counts = productNsoRepo.countByStatusMap();
        assertEquals(ProductNso.STATUSES, List.copyOf(counts.keySet()));
        assertTrue(counts.get(ProductNso.STATUS_CON_NSO) >= 2);
        assertTrue(counts.get(ProductNso.STATUS_EN_REVISION) >= 1);
        assertEquals(1L, productNsoRepo.countLinkedByCode().get("NSOC99921-25PE"));

        loaded.setStatus(ProductNso.STATUS_SIN_NSO);
        loaded.setNsoCode(null);
        loaded.setLocked(true);
        loaded.setDecidedBy("admin@aromastudio.pe");
        loaded.setDecidedAt(LocalDateTime.now());
        productNsoRepo.saveAndFlush(loaded);
        ProductNso again = productNsoRepo.findById(900001L).orElseThrow();
        assertEquals(ProductNso.STATUS_SIN_NSO, again.getStatus());
        assertTrue(again.getLocked());
        assertFalse(productNsoRepo.findConNsoProductIds().contains(900001L));
    }

    @Test
    void candidatosUnicosPorProductoYCodigo() {
        NsoCandidate c1 = new NsoCandidate(910001L, "NSOC99931-25PE", 0.82, 1, NsoCandidate.ORIGIN_MATCHER);
        c1.setReasonsJson("[\"concentración distinta: EDT vs EDP\"]");
        NsoCandidate c2 = new NsoCandidate(910001L, "NSOC99932-25PE", 0.70, 2, NsoCandidate.ORIGIN_RESEARCH);
        candidateRepo.saveAllAndFlush(List.of(c1, c2));
        assertNotNull(c1.getId(), "id por secuencia");
        assertNotEquals(c1.getId(), c2.getId());

        assertTrue(candidateRepo.existsByProductIdAndNsoCode(910001L, "NSOC99931-25PE"));
        assertFalse(candidateRepo.existsByProductIdAndNsoCode(910001L, "NSOC00000-25PE"));
        List<NsoCandidate> pending = candidateRepo.findByProductIdAndStatusOrderByRankAscIdAsc(910001L, NsoCandidate.STATUS_PENDING);
        assertEquals(List.of("NSOC99931-25PE", "NSOC99932-25PE"), pending.stream().map(NsoCandidate::getNsoCode).toList());
        assertEquals("[\"concentración distinta: EDT vs EDP\"]", pending.get(0).getReasonsJson(), "acentos intactos");
        assertTrue(candidateRepo.countProductsWithPending() >= 1);
        assertTrue(candidateRepo.findProductIdsByStatus(NsoCandidate.STATUS_PENDING).contains(910001L));

        NsoCandidate dup = new NsoCandidate(910001L, "NSOC99931-25PE", 0.5, 3, NsoCandidate.ORIGIN_MATCHER);
        assertThrows(DataIntegrityViolationException.class, () -> candidateRepo.saveAndFlush(dup));

        // Otro producto con el mismo codigo si puede.
        candidateRepo.saveAndFlush(new NsoCandidate(910002L, "NSOC99931-25PE", 0.9, 1, NsoCandidate.ORIGIN_MATCHER));

        assertEquals(2, candidateRepo.supersedePending(910001L, LocalDateTime.now()));
        assertTrue(candidateRepo.findByProductIdAndStatusOrderByRankAscIdAsc(910001L, NsoCandidate.STATUS_PENDING).isEmpty());
        assertEquals(NsoCandidate.STATUS_SUPERSEDED,
                candidateRepo.findByProductIdAndNsoCode(910001L, "NSOC99931-25PE").orElseThrow().getStatus());
        assertEquals(2, candidateRepo.deleteByProductId(910001L));
        assertEquals(1, candidateRepo.findByProductIdOrderByRankAscIdAsc(910002L).size());
    }

    @Test
    void aliasConClaveUnicaTambienParaMarca() {
        NsoAlias sku = new NsoAlias(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26", "NSOC99941-25PE",
                NsoAlias.POSITIVE, NsoAlias.ORIGIN_APROBADO);
        sku.setSourceProductId(920001L);
        sku.setCreatedBy("admin@aromastudio.pe");
        NsoAlias research = new NsoAlias(NsoAlias.KIND_GTIN, "06290171000976", "NSOC99941-25PE",
                NsoAlias.POSITIVE, NsoAlias.ORIGIN_RESEARCH);
        aliasRepo.saveAllAndFlush(List.of(sku, research));

        assertEquals(1, aliasRepo.findByKindAndAliasKey(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26").size());
        assertEquals("NSOC99941-25PE", sku.getCodeKey());
        assertTrue(aliasRepo.findUnique(NsoAlias.KIND_GTIN, "06290171000976", "NSOC99941-25PE").orElseThrow().isResearch());
        assertEquals(1, aliasRepo.findByKindAndAliasKeyIn(NsoAlias.KIND_GTIN, List.of("06290171000976", "00000000000000")).size());

        // Mismo (kind, key, codigo) -> unico, aunque cambie la polaridad.
        NsoAlias dupSku = new NsoAlias(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26", "NSOC99941-25PE",
                NsoAlias.NEGATIVE, NsoAlias.ORIGIN_RECHAZADO);
        assertThrows(DataIntegrityViolationException.class, () -> aliasRepo.saveAndFlush(dupSku));
        // La misma clave hacia OTRO codigo si se permite (el matcher lo reporta como conflicto).
        aliasRepo.saveAndFlush(new NsoAlias(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26", "NSOC99942-25PE",
                NsoAlias.NEGATIVE, NsoAlias.ORIGIN_RECHAZADO));
        assertEquals(2, aliasRepo.findByKindAndAliasKey(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26").size());

        // BRAND: nso_code null. Sin code_key dos NULL no chocarian en el UNIQUE; con code_key="*" si.
        NsoAlias brand = NsoAlias.brand("carolina", "carolina herrera", NsoAlias.ORIGIN_ADMIN);
        aliasRepo.saveAndFlush(brand);
        assertNull(brand.getNsoCode());
        assertEquals(NsoAlias.NO_CODE, brand.getCodeKey());
        NsoAlias brandDup = NsoAlias.brand("carolina", "otra marca", NsoAlias.ORIGIN_ADMIN);
        assertThrows(DataIntegrityViolationException.class, () -> aliasRepo.saveAndFlush(brandDup));
        assertEquals("carolina herrera", aliasRepo.findBrandAlias("carolina").orElseThrow().getTargetBrandKey());

        // Cambiar de opinion = actualizar la fila.
        NsoAlias flip = aliasRepo.findUnique(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26", "NSOC99941-25PE").orElseThrow();
        flip.setPolarity(NsoAlias.NEGATIVE);
        aliasRepo.saveAndFlush(flip);
        assertFalse(aliasRepo.findUnique(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26", "NSOC99941-25PE").orElseThrow().isPositive());

        assertTrue(aliasRepo.deleteByOrigin(NsoAlias.ORIGIN_RESEARCH) >= 1);
        assertTrue(aliasRepo.findByOrigin(NsoAlias.ORIGIN_RESEARCH).isEmpty());
        assertFalse(aliasRepo.findBySourceProductId(920001L).isEmpty(), "los alias de decisiones no se tocan");
    }

    @Test
    void eventosAppendOnlyMasNuevosPrimero() {
        eventRepo.saveAndFlush(new NsoEvent(NsoEvent.TYPE_STATUS_CHANGE, 930001L, null,
                ProductNso.STATUS_SIN_VERIFICAR, ProductNso.STATUS_EN_REVISION, "sistema", "primer paso"));
        eventRepo.saveAndFlush(new NsoEvent(NsoEvent.TYPE_CANDIDATE_ACCEPTED, 930001L, "NSOC99951-25PE",
                ProductNso.STATUS_EN_REVISION, ProductNso.STATUS_CON_NSO, "admin@aromastudio.pe", "Es este"));
        eventRepo.saveAndFlush(new NsoEvent(NsoEvent.TYPE_CATALOG_UPLOAD, null, null, null, null,
                "admin@aromastudio.pe", "x".repeat(2500)));

        List<NsoEvent> forProduct = eventRepo.recent(930001L, null, 10);
        assertEquals(2, forProduct.size());
        assertEquals(NsoEvent.TYPE_CANDIDATE_ACCEPTED, forProduct.get(0).getType(), "mas nuevo primero");
        assertNotNull(forProduct.get(0).getCreatedAt());

        assertEquals(1, eventRepo.recent(930001L, NsoEvent.TYPE_STATUS_CHANGE, 10).size());
        NsoEvent upload = eventRepo.findFirstByTypeOrderByCreatedAtDescIdDesc(NsoEvent.TYPE_CATALOG_UPLOAD).orElseThrow();
        assertEquals(2000, upload.getDetail().length(), "detalle recortado para no romper el insert");
        assertTrue(eventRepo.recent(null, "", 200).size() >= 3);
    }

    @Test
    void seedDeConfigNso() {
        assertEquals("false", configRepo.findByConfigKey("nso_gate_enabled").map(AppConfig::getConfigValue).orElseThrow());
        assertEquals("0.66", configRepo.findByConfigKey("nso_review_min_score").map(AppConfig::getConfigValue).orElseThrow());
        assertEquals("true", configRepo.findByConfigKey("nso_accept_can_codes").map(AppConfig::getConfigValue).orElseThrow());
        assertEquals("0", configRepo.findByConfigKey("nso_catalog_version").map(AppConfig::getConfigValue).orElseThrow());
        assertEquals("1", configRepo.findByConfigKey("nso_rules_version").map(AppConfig::getConfigValue).orElseThrow());
    }
}
