package org.example.backendbvaberiaperfumes.service.nso;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.config.CurrentAdminProvider;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.example.backendbvaberiaperfumes.model.NsoCandidate;
import org.example.backendbvaberiaperfumes.model.NsoEvent;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.NsoAliasRepository;
import org.example.backendbvaberiaperfumes.repository.NsoCandidateRepository;
import org.example.backendbvaberiaperfumes.repository.NsoEventRepository;
import org.example.backendbvaberiaperfumes.repository.NsoRecordRepository;
import org.example.backendbvaberiaperfumes.repository.ProductNsoRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Capa transaccional del sistema NSO: el parser y el matcher son puros; esta clase es el UNICO lugar que
 * persiste sus resultados (product_nso, nso_candidates, nso_aliases, nso_events) y la fuente de datos de
 * /api/admin/nso/**.
 *
 * Reglas de diseno:
 *  - No depende de ExcelImportService ni de ProductMergeService (evita ciclos): ellos la llaman a ella
 *    (rematchProducts, matchPreviewRows, onMerge, onProductDeleted).
 *  - Toda escritura pasa por write(): un ReentrantLock unico ENVUELVE la transaccion (el lock se suelta
 *    despues del commit), asi el rematch en segundo plano y las decisiones de la admin no se pisan.
 *  - El rematch masivo corre en bloques de 300, cada bloque en su propia transaccion; solo escribe las filas
 *    que cambiaron y respeta las decisiones bloqueadas de la admin.
 *  - Las decisiones de la admin se guardan como alias (GTIN / SKU del proveedor / NAME_KEY): una aprobacion
 *    cubre otros tamanos y proveedores del mismo perfume, y un rechazo no se vuelve a proponer.
 */
@Service
public class NsoService {

    public static final String CFG_GATE = NsoGate.CFG_GATE;
    public static final String CFG_ACCEPT_CAN = NsoGate.CFG_ACCEPT_CAN;
    public static final String CFG_REVIEW_MIN = "nso_review_min_score";
    public static final String CFG_CATALOG_VERSION = "nso_catalog_version";
    public static final String CFG_LAST_UPLOAD_AT = "nso_last_upload_at";
    public static final String CFG_LAST_UPLOAD_BY = "nso_last_upload_by";
    public static final String CFG_LAST_UPLOAD_FILENAME = "nso_last_upload_filename";

    /** Tamano del bloque del rematch masivo (cada bloque = una transaccion). */
    public static final int REMATCH_BLOCK = 300;
    /** Actor de los eventos cuando no hay admin autenticado (rematch en segundo plano, tests). */
    public static final String SYSTEM_ACTOR = "sistema";

    private static final int IN_CHUNK = 500;
    private static final int JSON_MAX = 4000;
    private static final int ALIAS_KEY_MAX = 400;

    private final NsoCatalogParser parser;
    private final NsoRecordRepository recordRepo;
    private final ProductNsoRepository productNsoRepo;
    private final NsoCandidateRepository candidateRepo;
    private final NsoAliasRepository aliasRepo;
    private final NsoEventRepository eventRepo;
    private final ProductRepository productRepo;
    private final SupplierOfferRepository offerRepo;
    private final AppConfigRepository configRepo;
    private final NsoGate gate;
    private final CurrentAdminProvider currentAdmin;
    private final ObjectMapper json;
    private final ObjectProvider<NsoService> self;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    /** Solo para runAfterCommit: en afterCommit un REQUIRED se uniria a la transaccion ya terminada. */
    private final TransactionTemplate requiresNewTx;

    private final ReentrantLock lock = new ReentrantLock();

    // --- progreso del rematch masivo (en memoria: summary.rematch) ---
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean workerActive = new AtomicBoolean(false);
    private volatile boolean rerunRequested;
    private volatile int progressProcessed;
    private volatile int progressTotal;
    private volatile Instant progressStartedAt;
    private volatile Instant progressFinishedAt;
    private volatile String progressError;

    public NsoService(NsoCatalogParser parser, NsoRecordRepository recordRepo, ProductNsoRepository productNsoRepo,
                      NsoCandidateRepository candidateRepo, NsoAliasRepository aliasRepo, NsoEventRepository eventRepo,
                      ProductRepository productRepo, SupplierOfferRepository offerRepo, AppConfigRepository configRepo,
                      NsoGate gate, CurrentAdminProvider currentAdmin, ObjectMapper json,
                      ObjectProvider<NsoService> self, PlatformTransactionManager txManager) {
        this.parser = parser;
        this.recordRepo = recordRepo;
        this.productNsoRepo = productNsoRepo;
        this.candidateRepo = candidateRepo;
        this.aliasRepo = aliasRepo;
        this.eventRepo = eventRepo;
        this.productRepo = productRepo;
        this.offerRepo = offerRepo;
        this.configRepo = configRepo;
        this.gate = gate;
        this.currentAdmin = currentAdmin;
        this.json = json;
        this.self = self;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // =====================================================================
    // Tipos publicos (peticiones y resultados)
    // =====================================================================

    /** Codigo NSO elegido a mano (assign y alta de producto). createIfMissing crea el codigo MANUAL si no existe. */
    public static class ManualCode {
        public String code;
        public Boolean createIfMissing;
        public String brand;
        public String declaredName;
        public String titular;
        public String ruc;

        public ManualCode() {}

        public ManualCode(String code) {
            this.code = code;
        }
    }

    /** Codigo nuevo agregado a mano al catalogo (POST /catalog). */
    public static class NewRecord {
        public String code;
        public String brand;
        public String declaredName;
        public String titular;
        public String ruc;
    }

    /** Cambio de estado de un producto tras evaluarlo. */
    public record Change(Long productId, String fromStatus, String toStatus, String fromCode, String toCode,
                         boolean written) {
        public boolean statusOrCodeChanged() {
            return !Objects.equals(fromStatus, toStatus) || !Objects.equals(fromCode, toCode);
        }

        public boolean becameConNso() {
            return ProductNso.STATUS_CON_NSO.equals(toStatus)
                    && (!ProductNso.STATUS_CON_NSO.equals(fromStatus) || !Objects.equals(fromCode, toCode));
        }
    }

    /** Resultado de un rematch sincrono: procesados, cambios y conteo de estados resultantes. */
    public static class RematchOutcome {
        private int processed;
        private final List<Change> changes = new ArrayList<>();
        private final Map<String, Integer> statusCounts = new LinkedHashMap<>();

        RematchOutcome() {
            for (String s : ProductNso.STATUSES) statusCounts.put(s, 0);
        }

        public int getProcessed() { return processed; }
        public List<Change> getChanges() { return changes; }
        public Map<String, Integer> getStatusCounts() { return statusCounts; }
        public int count(String status) { return statusCounts.getOrDefault(status, 0); }
    }

    /** Veredicto NSO de una fila del preview de import (contrato ImportPreview.Line.nso*). */
    public static class RowVerdict {
        private String status;
        private String nsoCode;
        private String declaredName;
        private String titular;
        private String reason;
        private String country;

        public String getStatus() { return status; }
        public String getNsoCode() { return nsoCode; }
        public String getDeclaredName() { return declaredName; }
        public String getTitular() { return titular; }
        public String getReason() { return reason; }
        public String getCountry() { return country; }
    }

    /** Resultado del preview: un veredicto por fila (mismo orden) + contadores. */
    public static class PreviewResult {
        private boolean catalogLoaded;
        private final List<RowVerdict> rows = new ArrayList<>();
        private int conNso;
        private int review;
        private int brandOnly;
        private int none;

        public boolean isCatalogLoaded() { return catalogLoaded; }
        public List<RowVerdict> getRows() { return rows; }
        public int getConNso() { return conNso; }
        public int getReview() { return review; }
        public int getBrandOnly() { return brandOnly; }
        public int getNone() { return none; }
    }

    // =====================================================================
    // 1. Carga del catalogo
    // =====================================================================

    /**
     * Sube la lista NSO (.xlsx o .csv). Upsert por codigo (nunca borra); los ausentes quedan inLastUpload=false
     * SOLO si el archivo trajo registros (los codigos agregados a mano no cuentan como ausentes); los links de
     * investigacion (RESEARCH) se reemplazan solo si el archivo los trae. Tras el commit lanza el rematch masivo.
     * @throws IllegalArgumentException si el archivo no se reconoce (400).
     */
    public Map<String, Object> uploadCatalog(String filename, byte[] bytes) {
        String actor = actor();
        NsoCatalogParser.ParsedCatalog parsed = parser.parse(filename, bytes);
        return write(() -> {
            Map<String, NsoRecord> existing = new HashMap<>();
            for (NsoRecord r : recordRepo.findAll()) existing.put(r.getCode(), r);

            int inserted = 0, updated = 0, unchanged = 0;
            Set<String> inFile = new HashSet<>();
            List<NsoRecord> toInsert = new ArrayList<>();
            for (NsoRecord incoming : parsed.records) {
                if (incoming.getCode() == null || !inFile.add(incoming.getCode())) continue;
                NsoRecord old = existing.get(incoming.getCode());
                if (old == null) {
                    incoming.setInLastUpload(true);
                    if (incoming.getActive() == null) incoming.setActive(true);
                    toInsert.add(incoming);
                    inserted++;
                } else {
                    boolean changed = old.applyCatalogData(incoming);
                    if (!Boolean.TRUE.equals(old.getInLastUpload())) old.setInLastUpload(true);
                    if (changed) updated++; else unchanged++;
                }
            }
            if (!toInsert.isEmpty()) recordRepo.saveAll(toInsert);

            int missing = 0;
            if (!parsed.records.isEmpty()) {
                for (NsoRecord r : existing.values()) {
                    if (inFile.contains(r.getCode()) || NsoRecord.SOURCE_MANUAL.equals(r.getSource())) continue;
                    missing++;
                    if (!Boolean.FALSE.equals(r.getInLastUpload())) r.setInLastUpload(false);
                }
            }

            // Links de investigacion: se reemplazan. Una decision de la admin con la misma clave prevalece.
            if (!parsed.researchLinks.isEmpty()) {
                aliasRepo.deleteByOrigin(NsoAlias.ORIGIN_RESEARCH);
                Set<String> taken = new HashSet<>();
                for (NsoAlias a : aliasRepo.findAll()) taken.add(aliasIdentity(a));
                List<NsoAlias> research = new ArrayList<>();
                for (NsoAlias a : parsed.researchAliases()) {
                    if (a.getAliasKey() == null || a.getAliasKey().length() > ALIAS_KEY_MAX) continue;
                    if (taken.add(aliasIdentity(a))) {
                        a.setCreatedBy(actor);
                        research.add(a);
                    }
                }
                if (!research.isEmpty()) aliasRepo.saveAll(research);
            }

            int version = bumpVersion();
            setConfig(CFG_LAST_UPLOAD_AT, Instant.now().toString(), "Ultima carga de la lista NSO (no editar a mano)");
            setConfig(CFG_LAST_UPLOAD_BY, actor, "Quien subio la ultima lista NSO (no editar a mano)");
            setConfig(CFG_LAST_UPLOAD_FILENAME, filename == null ? "" : filename,
                    "Archivo de la ultima lista NSO (no editar a mano)");
            event(NsoEvent.TYPE_CATALOG_UPLOAD, null, null, null, null, actor,
                    "Lista NSO «" + (filename == null ? "sin nombre" : filename) + "» (" + parsed.fileType + "): "
                            + parsed.records.size() + " códigos leídos, " + inserted + " nuevos, " + updated
                            + " actualizados, " + unchanged + " sin cambios, " + parsed.invalidRows.size()
                            + " filas con error, " + missing + " ya no vienen en el archivo; versión " + version);
            gate.invalidate();

            boolean rematchStarted = recordRepo.countByActiveTrue() > 0 && requestRematchAll(actor, false);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fileType", parsed.fileType);
            out.put("filename", filename);
            out.put("recordsRead", parsed.records.size());
            out.put("inserted", inserted);
            out.put("updated", updated);
            out.put("unchanged", unchanged);
            List<Map<String, Object>> invalid = new ArrayList<>();
            for (NsoCatalogParser.InvalidRow row : parsed.invalidRows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("row", row.row);
                m.put("raw", row.raw);
                m.put("reason", row.reason);
                invalid.add(m);
            }
            out.put("invalidRows", invalid);
            out.put("missingFromFile", missing);
            out.put("researchLinksRead", parsed.researchLinks.size());
            out.put("catalogVersion", version);
            out.put("rematchStarted", rematchStarted);
            return out;
        });
    }

    // =====================================================================
    // 2. Rematch
    // =====================================================================

    /** "Volver a verificar todo" en segundo plano. @throws NsoConflictException si ya corre (409). */
    public boolean startRematchAll() {
        return requestRematchAll(actor(), true);
    }

    /**
     * Trabajador del rematch masivo (@Async: llamarlo via startRematchAll o el proxy). Bloques de 300, cada uno en
     * su propia transaccion y con el lock; re-chequea el bloqueo de cada fila; si durante la corrida se pidio otra
     * (p. ej. se subio otra lista) vuelve a correr al terminar.
     */
    @Async
    public void rematchAllAsync(String actor) {
        running.set(true);
        if (!workerActive.compareAndSet(false, true)) {
            rerunRequested = true;
            return;
        }
        String who = actor == null ? SYSTEM_ACTOR : actor;
        try {
            do {
                rerunRequested = false;
                progressStartedAt = Instant.now();
                progressFinishedAt = null;
                progressError = null;
                runFullRematch(who);
            } while (rerunRequested);
        } catch (RuntimeException e) {
            progressError = "No se pudo terminar la verificación: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            System.err.println("[NSO] Rematch masivo fallo: " + e);
        } finally {
            progressFinishedAt = Instant.now();
            workerActive.set(false);
            running.set(false);
            // Pedido que llego justo mientras terminaba: no se pierde.
            if (rerunRequested && running.compareAndSet(false, true)) {
                resetProgress();
                launchWorker(who);
            }
        }
    }

    /** Re-verifica esos productos YA (sincrono, en la transaccion en curso si la hay). */
    public RematchOutcome rematchProducts(Collection<Long> productIds) {
        String actor = actor();
        return write(() -> rematchInternal(productIds, actor, true));
    }

    /** Progreso del rematch masivo (summary.rematch). */
    public Map<String, Object> rematchProgress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running.get());
        m.put("processed", progressProcessed);
        m.put("total", progressTotal);
        m.put("startedAt", progressStartedAt);
        m.put("finishedAt", progressFinishedAt);
        m.put("error", progressError);
        return m;
    }

    public boolean isRematchRunning() {
        return running.get();
    }

    /** Espera a que termine el rematch masivo (tests/diagnostico). true si quedo libre antes del timeout. */
    public boolean awaitRematchIdle(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (running.get()) {
            if (System.currentTimeMillis() > end) return false;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private boolean requestRematchAll(String actor, boolean failIfRunning) {
        if (!running.compareAndSet(false, true)) {
            if (failIfRunning) {
                throw new NsoConflictException("Ya se está verificando todo el catálogo. Espera a que termine.");
            }
            rerunRequested = true;
            return true;
        }
        resetProgress();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) launchWorker(actor);
                    else running.set(false);
                }
            });
        } else {
            launchWorker(actor);
        }
        return true;
    }

    private void launchWorker(String actor) {
        try {
            self.getObject().rematchAllAsync(actor);
        } catch (RuntimeException e) {
            running.set(false);
            progressError = "No se pudo iniciar la verificación: " + e.getMessage();
        }
    }

    private void resetProgress() {
        progressProcessed = 0;
        progressTotal = 0;
        progressStartedAt = Instant.now();
        progressFinishedAt = null;
        progressError = null;
    }

    private void runFullRematch(String actor) {
        if (recordRepo.count() == 0) {
            // Sin lista cargada nunca: no hay contra que verificar (todo sigue SIN_VERIFICAR).
            progressTotal = 0;
            progressProcessed = 0;
            return;
        }
        Session session = readTx.execute(s -> openFullSession());
        List<Long> ids = new ArrayList<>(session.products.keySet());
        ids.sort(Comparator.naturalOrder());
        progressTotal = ids.size();
        progressProcessed = 0;
        int changed = 0;
        for (int from = 0; from < ids.size(); from += REMATCH_BLOCK) {
            List<Long> block = ids.subList(from, Math.min(ids.size(), from + REMATCH_BLOCK));
            Integer n = write(() -> processBlock(session, block, actor));
            changed += n == null ? 0 : n;
            progressProcessed = Math.min(ids.size(), from + block.size());
        }
        int total = ids.size();
        int changedFinal = changed;
        write(() -> {
            event(NsoEvent.TYPE_REMATCH, null, null, null, null, actor,
                    "Verificación completa: " + total + " perfumes revisados, " + changedFinal + " cambiaron");
            return null;
        });
        gate.invalidate();
    }

    private int processBlock(Session s, List<Long> block, String actor) {
        // Productos borrados o archivados durante la corrida: no se les escribe fila.
        Set<Long> alive = productsById(block).keySet();
        Map<Long, ProductNso> states = statesOf(block);
        Map<Long, List<NsoCandidate>> cands = candidatesOf(block);
        int changed = 0;
        for (Long pid : block) {
            if (!alive.contains(pid)) continue;
            ProductNso state = states.get(pid);
            if (state != null && Boolean.TRUE.equals(state.getLocked())) {
                String code = state.getNsoCode();
                // Decision de la admin: se respeta mientras su codigo siga activo (se re-lee: pudo crearse despues
                // de armar el indice de esta corrida).
                if (code == null || s.matcher.index().isActiveCode(code)) continue;
                boolean activeNow = recordRepo.findById(code).map(r -> Boolean.TRUE.equals(r.getActive())).orElse(false);
                if (activeNow) continue;
            }
            Change ch = evaluate(s, pid, state, cands.getOrDefault(pid, List.of()), actor, false);
            if (ch != null && ch.written()) changed++;
        }
        return changed;
    }

    private RematchOutcome rematchInternal(Collection<Long> productIds, String actor, boolean logInitial) {
        RematchOutcome out = new RematchOutcome();
        List<Long> ids = distinctIds(productIds);
        // Sin lista NSO cargada nunca: no se escribe nada (los productos siguen SIN_VERIFICAR).
        if (ids.isEmpty() || recordRepo.count() == 0) return out;
        Session s = openScopedSession(ids, List.of());
        Map<Long, ProductNso> states = statesOf(ids);
        Map<Long, List<NsoCandidate>> cands = candidatesOf(ids);
        for (Long pid : ids) {
            if (!s.products.containsKey(pid)) continue;
            Change ch = evaluate(s, pid, states.get(pid), cands.getOrDefault(pid, List.of()), actor, logInitial);
            if (ch == null) continue;
            out.processed++;
            out.statusCounts.merge(ch.toStatus(), 1, Integer::sum);
            if (ch.written()) out.changes.add(ch);
        }
        gate.invalidate();
        return out;
    }

    /** Evalua un producto con el matcher y persiste solo si cambio algo. */
    private Change evaluate(Session s, Long pid, ProductNso state, List<NsoCandidate> existing, String actor,
                            boolean logInitial) {
        Product product = s.products.get(pid);
        if (product == null) return null;
        NsoMatcher.Evidence e = s.evidence(pid);
        if (state != null && Boolean.TRUE.equals(state.getLocked())) {
            e.locked(state.getStatus(), state.getNsoCode(), state.getMatchedBy());
        }
        NsoMatcher.Result r = s.matcher.resolve(e);
        return apply(pid, state, r, existing, s.catalogVersion, actor, logInitial);
    }

    private Change apply(Long pid, ProductNso state, NsoMatcher.Result r, List<NsoCandidate> existing, int version,
                         String actor, boolean logInitial) {
        String fromStatus = state == null ? ProductNso.STATUS_SIN_VERIFICAR : state.getStatus();
        String fromCode = state == null ? null : state.getNsoCode();
        boolean wasLocked = state != null && Boolean.TRUE.equals(state.getLocked());
        boolean keepLock = wasLocked && Objects.equals(fromStatus, r.status) && Objects.equals(fromCode, r.nsoCode);
        if (keepLock) {
            // Decision de la admin vigente: no se toca ni la fila ni sus candidatos.
            return new Change(pid, fromStatus, fromStatus, fromCode, fromCode, false);
        }

        boolean written = false;
        String reasonsJson = toJsonList(r.reasons);
        ProductNso target = state;
        if (target == null) {
            target = new ProductNso(pid, r.status);
            written = true;
        }
        if (wasLocked) {
            // El codigo que eligio ya no esta activo: el resultado automatico no hereda el bloqueo.
            target.setLocked(false);
            target.setDecidedBy(null);
            target.setDecidedAt(null);
            written = true;
        }
        if (written || !Objects.equals(target.getStatus(), r.status) || !Objects.equals(target.getNsoCode(), r.nsoCode)
                || !Objects.equals(target.getMatchedBy(), r.matchedBy) || !Objects.equals(target.getScore(), r.score)
                || !Objects.equals(target.getReasonsJson(), reasonsJson) || !Objects.equals(target.getBrandKey(), r.brandKey)) {
            target.setStatus(r.status);
            target.setNsoCode(r.nsoCode);
            target.setMatchedBy(r.matchedBy);
            target.setScore(r.score);
            target.setReasonsJson(reasonsJson);
            target.setBrandKey(r.brandKey);
            target.setCheckedAt(LocalDateTime.now());
            target.setCatalogVersion(version);
            productNsoRepo.save(target);
            written = true;
        }
        if (syncCandidates(pid, r, existing, actor)) written = true;

        if ((state != null || logInitial)
                && (!Objects.equals(fromStatus, r.status) || !Objects.equals(fromCode, r.nsoCode))) {
            event(NsoEvent.TYPE_STATUS_CHANGE, pid, r.nsoCode, fromStatus, r.status, actor,
                    String.join("; ", r.reasons));
        }
        return new Change(pid, fromStatus, r.status, fromCode, r.nsoCode, written);
    }

    /**
     * Candidatos: uno por (producto, codigo); un REJECTED nunca se re-crea; los PENDING que ya no aplican pasan a
     * SUPERSEDED. Devuelve true si escribio algo.
     */
    private boolean syncCandidates(Long pid, NsoMatcher.Result r, List<NsoCandidate> existing, String actor) {
        boolean written = false;
        Map<String, NsoCandidate> byCode = new HashMap<>();
        for (NsoCandidate c : existing) byCode.put(c.getNsoCode(), c);
        Set<String> proposed = new HashSet<>();
        int rank = 1;
        if (!r.isConNso()) {
            for (NsoMatcher.Candidate c : r.candidates) {
                if (!proposed.add(c.code)) continue;
                int thisRank = rank++;
                String rj = toJsonList(c.reasons);
                Double score = c.score;
                NsoCandidate n = byCode.get(c.code);
                if (n == null) {
                    n = new NsoCandidate(pid, c.code, score, thisRank, c.origin);
                    n.setReasonsJson(rj);
                    candidateRepo.save(n);
                    written = true;
                    continue;
                }
                if (NsoCandidate.STATUS_REJECTED.equals(n.getStatus())) continue;
                if (!NsoCandidate.STATUS_PENDING.equals(n.getStatus()) || !Objects.equals(n.getScore(), score)
                        || !Objects.equals(n.getRank(), thisRank) || !Objects.equals(n.getOrigin(), c.origin)
                        || !Objects.equals(n.getReasonsJson(), rj)) {
                    n.setStatus(NsoCandidate.STATUS_PENDING);
                    n.setScore(score);
                    n.setRank(thisRank);
                    n.setOrigin(c.origin);
                    n.setReasonsJson(rj);
                    n.setResolvedAt(null);
                    n.setResolvedBy(null);
                    candidateRepo.save(n);
                    written = true;
                }
            }
        }
        for (NsoCandidate n : existing) {
            if (NsoCandidate.STATUS_PENDING.equals(n.getStatus()) && !proposed.contains(n.getNsoCode())) {
                n.setStatus(NsoCandidate.STATUS_SUPERSEDED);
                n.setResolvedAt(LocalDateTime.now());
                n.setResolvedBy(actor);
                candidateRepo.save(n);
                written = true;
            }
        }
        return written;
    }

    // =====================================================================
    // 3. Decisiones de la admin
    // =====================================================================

    /**
     * "Es este": CON_NSO bloqueado (APROBADO) + alias positivos (GTIN, SKU de cada oferta, NAME_KEY); los demas
     * candidatos del producto quedan SUPERSEDED; se re-verifican ya los productos no bloqueados de la marca.
     * Respuesta: {productId, status, nsoCode, alsoResolved:[ids que pasaron a CON_NSO]}.
     */
    public Map<String, Object> accept(Long candidateId) {
        String actor = actor();
        return write(() -> {
            NsoCandidate c = candidateRepo.findById(candidateId).orElseThrow(() ->
                    new NsoNotFoundException("Esa opción ya no existe. Actualiza la lista de revisión."));
            if (!NsoCandidate.STATUS_PENDING.equals(c.getStatus())) {
                throw new NsoConflictException("Esa opción ya fue resuelta. Actualiza la lista de revisión.");
            }
            Product product = liveProduct(c.getProductId());
            NsoRecord record = recordRepo.findById(c.getNsoCode()).orElse(null);
            if (record == null || !Boolean.TRUE.equals(record.getActive())) {
                throw new NsoConflictException("El código " + c.getNsoCode() + " ya no está activo en tu lista de NSO.");
            }
            List<String> reasons = new ArrayList<>();
            reasons.add("lo aprobaste tú: «" + record.getDeclaredName() + "»");
            reasons.addAll(parseList(c.getReasonsJson()));
            String brandKey = decide(product, record, ProductNso.MATCHED_APROBADO, NsoAlias.ORIGIN_APROBADO, actor,
                    c.getScore(), reasons);
            event(NsoEvent.TYPE_CANDIDATE_ACCEPTED, product.getId(), record.getCode(), null, ProductNso.STATUS_CON_NSO,
                    actor, "Aprobaste " + record.getCode() + " «" + record.getDeclaredName() + "»");
            List<Long> alsoResolved = new ArrayList<>();
            for (Change ch : rematchBrand(brandKey, product.getId(), actor).changes) {
                if (ch.becameConNso()) alsoResolved.add(ch.productId());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("productId", product.getId());
            out.put("status", ProductNso.STATUS_CON_NSO);
            out.put("nsoCode", record.getCode());
            out.put("alsoResolved", alsoResolved);
            return out;
        });
    }

    /**
     * "Ninguno": candidatos PENDING -> REJECTED + alias NEGATIVE NAME_KEY por codigo; recalcula el producto SIN
     * bloquearlo (codigos nuevos que lleguen despues si pueden proponerse). Respuesta {productId, status}.
     */
    public Map<String, Object> rejectAll(Long productId) {
        String actor = actor();
        return write(() -> {
            Product product = liveProduct(productId);
            List<NsoCandidate> pending = candidateRepo.findByProductIdAndStatusOrderByRankAscIdAsc(productId,
                    NsoCandidate.STATUS_PENDING);
            if (!pending.isEmpty()) {
                Session s = openScopedSession(List.of(productId), List.of());
                List<String> nameKeys = s.matcher.nameKeys(s.evidence(productId));
                List<String> codes = new ArrayList<>();
                for (NsoCandidate c : pending) {
                    c.setStatus(NsoCandidate.STATUS_REJECTED);
                    c.setResolvedAt(LocalDateTime.now());
                    c.setResolvedBy(actor);
                    candidateRepo.save(c);
                    codes.add(c.getNsoCode());
                    for (String nk : nameKeys) {
                        upsertAlias(NsoAlias.KIND_NAME_KEY, nk, c.getNsoCode(), NsoAlias.NEGATIVE,
                                NsoAlias.ORIGIN_RECHAZADO, productId, actor);
                    }
                }
                event(NsoEvent.TYPE_CANDIDATES_REJECTED, productId, null, null, null, actor,
                        "Descartaste " + codes.size() + " opción(es): " + String.join(", ", codes));
            }
            rematchInternal(List.of(productId), actor, true);
            return statusResult(product.getId());
        });
    }

    /**
     * Asigna un codigo a mano: CON_NSO bloqueado (MANUAL) + los mismos alias que aprobar.
     * @throws IllegalArgumentException formato invalido (400); NsoNotFoundException producto o codigo inexistente
     *         (404; canCreate=true si el codigo no esta en la lista); NsoConflictException codigo desactivado (409).
     */
    public Map<String, Object> assignManual(Long productId, ManualCode req) {
        String actor = actor();
        String code = canonicalOrThrow(req == null ? null : req.code);
        return write(() -> {
            Product product = liveProduct(productId);
            NsoRecord record = ensureRecord(code, req, product, actor);
            String brandKey = decide(product, record, ProductNso.MATCHED_MANUAL, NsoAlias.ORIGIN_MANUAL, actor, null,
                    List.of("asignado a mano por ti: «" + record.getDeclaredName() + "»"));
            event(NsoEvent.TYPE_MANUAL_ASSIGN, productId, code, null, ProductNso.STATUS_CON_NSO, actor,
                    "Asignaste a mano " + code + " «" + record.getDeclaredName() + "»");
            rematchBrand(brandKey, productId, actor);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("productId", productId);
            out.put("status", ProductNso.STATUS_CON_NSO);
            out.put("nsoCode", code);
            return out;
        });
    }

    /**
     * Quitar el NSO: el par (producto, codigo) queda rechazado + alias NEGATIVE (nombre, SKU, codigo de barras);
     * se desbloquea y se recalcula. Respuesta {productId, status}.
     */
    public Map<String, Object> unassign(Long productId) {
        String actor = actor();
        return write(() -> {
            liveProduct(productId);
            ProductNso state = productNsoRepo.findById(productId).orElse(null);
            String code = state == null ? null : state.getNsoCode();
            String from = state == null ? ProductNso.STATUS_SIN_VERIFICAR : state.getStatus();
            if (code != null) {
                NsoCandidate c = candidateRepo.findByProductIdAndNsoCode(productId, code).orElse(null);
                if (c == null) {
                    c = new NsoCandidate(productId, code, state.getScore(), 99, NsoCandidate.ORIGIN_MATCHER);
                    c.setReasonsJson(toJsonList(List.of("lo quitaste tú")));
                }
                c.setStatus(NsoCandidate.STATUS_REJECTED);
                c.setResolvedAt(LocalDateTime.now());
                c.setResolvedBy(actor);
                candidateRepo.save(c);
                Session s = openScopedSession(List.of(productId), List.of());
                AliasKeys keys = aliasKeys(s, productId);
                for (String k : keys.gtins) upsertAlias(NsoAlias.KIND_GTIN, k, code, NsoAlias.NEGATIVE, NsoAlias.ORIGIN_RECHAZADO, productId, actor);
                for (String k : keys.skus) upsertAlias(NsoAlias.KIND_SUPPLIER_SKU, k, code, NsoAlias.NEGATIVE, NsoAlias.ORIGIN_RECHAZADO, productId, actor);
                for (String k : keys.nameKeys) upsertAlias(NsoAlias.KIND_NAME_KEY, k, code, NsoAlias.NEGATIVE, NsoAlias.ORIGIN_RECHAZADO, productId, actor);
            }
            if (state != null && Boolean.TRUE.equals(state.getLocked())) {
                state.setLocked(false);
                state.setDecidedBy(null);
                state.setDecidedAt(null);
                productNsoRepo.save(state);
            }
            event(NsoEvent.TYPE_UNASSIGN, productId, code, from, null, actor,
                    code == null ? "Quitaste el NSO (no tenía código)" : "Quitaste " + code + " de este perfume");
            rematchInternal(List.of(productId), actor, true);
            return statusResult(productId);
        });
    }

    /**
     * Activa/desactiva un codigo. confirm=false solo previsualiza (desactivar: perfumes CON_NSO con ese codigo).
     * Con confirm, al desactivar se desbloquean y recalculan los afectados; al activar se re-verifican los perfumes
     * no bloqueados de esa marca. Respuesta {applied, affectedProducts:[{id, brand, name}]}.
     */
    public Map<String, Object> setCodeActive(String code, boolean active, boolean confirm) {
        String canonical = canonicalOrThrow(code);
        String actor = actor();
        Supplier<Map<String, Object>> body = () -> {
            NsoRecord r = recordRepo.findById(canonical).orElseThrow(() ->
                    new NsoNotFoundException("El código " + canonical + " no está en tu lista de NSO."));
            List<Product> affected = new ArrayList<>();
            if (!active) {
                List<Long> ids = new ArrayList<>();
                for (ProductNso st : productNsoRepo.findByNsoCodeAndStatus(canonical, ProductNso.STATUS_CON_NSO)) ids.add(st.getProductId());
                affected.addAll(productsById(ids).values());
                affected.sort(Comparator.comparing((Product p) -> nz(p.getBrand())).thenComparing(p -> nz(p.getName())));
            }
            List<Map<String, Object>> affectedView = new ArrayList<>();
            for (Product p : affected) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("brand", p.getBrand());
                m.put("name", p.getName());
                affectedView.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            if (!confirm) {
                out.put("applied", false);
                out.put("affectedProducts", affectedView);
                return out;
            }
            if (!Objects.equals(Boolean.TRUE.equals(r.getActive()), active)) {
                r.setActive(active);
                recordRepo.save(r);
                bumpVersion();
                event(active ? NsoEvent.TYPE_CODE_ACTIVATED : NsoEvent.TYPE_CODE_DEACTIVATED, null, canonical, null, null,
                        actor, (active ? "Activaste " : "Desactivaste ") + canonical + " «" + r.getDeclaredName() + "»"
                                + (active ? "" : "; " + affected.size() + " perfume(s) afectados"));
                if (!active) {
                    List<Long> ids = new ArrayList<>();
                    for (ProductNso st : productNsoRepo.findByNsoCode(canonical)) {
                        ids.add(st.getProductId());
                        if (Boolean.TRUE.equals(st.getLocked())) {
                            st.setLocked(false);
                            st.setDecidedBy(null);
                            st.setDecidedAt(null);
                            productNsoRepo.save(st);
                        }
                    }
                    rematchInternal(ids, actor, true);
                } else {
                    rematchInternal(productIdsOfCatalogBrand(r.getBrandKey() != null ? r.getBrandKey() : r.getBrand()), actor, true);
                }
                gate.invalidate();
            }
            out.put("applied", true);
            out.put("affectedProducts", affectedView);
            return out;
        };
        return confirm ? write(body) : read(body);
    }

    /** "Esta marca es la misma que...": alias BRAND de la admin + re-verificacion de esa marca. {ok, rematched}. */
    public Map<String, Object> addBrandAlias(String supplierBrand, String catalogBrandKey) {
        String actor = actor();
        String from = NsoKeys.brandKey(supplierBrand);
        if (from.isEmpty() || NsoKeys.brandKey(catalogBrandKey).isEmpty()) {
            throw new IllegalArgumentException("Indica la marca del proveedor y la marca de tu lista de NSO.");
        }
        return write(() -> {
            NsoBrandDictionary dict = NsoBrandDictionary.build(recordRepo.findByActiveTrue(), aliasRepo.findByKind(NsoAlias.KIND_BRAND));
            // Se pliega con la MISMA funcion del diccionario (NsoKeys.brandKey): sirve la clave ya plegada o el nombre
            // de marca tal cual ("Dolce & Gabbana", "dolce and gabbana", "MONT BLANC"). Si aun asi no es una marca del
            // catalogo, se intenta reconocerla como lo hace el matcher (alias fijos, sin espacios, contencion).
            String folded = NsoKeys.brandKey(catalogBrandKey);
            String target = dict.canonicalKey(folded);
            if (!dict.hasCatalogRecords(target)) {
                NsoBrandDictionary.BrandMatch m = dict.resolve(folded);
                if (m.isResolved() && dict.hasCatalogRecords(m.brandKey)) target = m.brandKey;
            }
            if (!dict.hasCatalogRecords(target)) {
                throw new IllegalArgumentException("La marca «" + catalogBrandKey + "» no está en tu lista de NSO.");
            }
            NsoAlias a = aliasRepo.findBrandAlias(from).orElse(null);
            if (a == null) {
                a = NsoAlias.brand(from, target, NsoAlias.ORIGIN_ADMIN);
            } else {
                a.setTargetBrandKey(target);
                a.setPolarity(NsoAlias.POSITIVE);
                a.setOrigin(NsoAlias.ORIGIN_ADMIN);
            }
            a.setCreatedBy(actor);
            aliasRepo.save(a);
            event(NsoEvent.TYPE_BRAND_ALIAS, null, null, null, null, actor,
                    "«" + supplierBrand.trim() + "» es la misma marca que «" + dict.displayName(target) + "»");
            Set<Long> ids = new LinkedHashSet<>(productIdsOfCatalogBrand(target));
            for (ProductNso st : productNsoRepo.findByBrandKeyAndLockedFalse(from)) ids.add(st.getProductId());
            RematchOutcome o = rematchInternal(ids, actor, true);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("rematched", o.processed);
            return out;
        });
    }

    /** Enciende/apaga el filtro publico. @throws NsoConflictException al encender sin codigos activos (409). */
    public Map<String, Object> setGate(boolean enabled) {
        String actor = actor();
        write(() -> {
            if (enabled && recordRepo.countByActiveTrue() == 0) {
                throw new NsoConflictException("Primero sube tu lista de NSO: sin lista, la tienda quedaría vacía.");
            }
            boolean before = "true".equalsIgnoreCase(value(CFG_GATE, "false").trim());
            setConfig(CFG_GATE, Boolean.toString(enabled), "Mostrar y comprar solo perfumes con NSO (true/false)");
            if (before != enabled) {
                event(enabled ? NsoEvent.TYPE_GATE_ON : NsoEvent.TYPE_GATE_OFF, null, null, null, null, actor,
                        enabled ? "Activaste «mostrar solo perfumes con NSO»" : "Apagaste el filtro NSO: la tienda muestra todo");
            }
            gate.invalidate();
            return null;
        });
        return summary();
    }

    /** Rango permitido del umbral de revision (nso_review_min_score). */
    public static final double REVIEW_MIN_LOW = 0.5;
    public static final double REVIEW_MIN_HIGH = 0.95;

    /**
     * PUT /settings: opciones del panel que NO son el filtro. acceptCanCodes = los NSO de Colombia/Bolivia/Ecuador
     * cuentan; reviewMinScore (0.5..0.95) = parecido minimo para proponer una opcion a revision. Guarda en app_config,
     * deja evento, invalida el gate y, si algo cambio y hay lista cargada, re-verifica todo en segundo plano (ambas
     * cambian lo que el matcher decide). Devuelve el summary.
     * @throws IllegalArgumentException sin opciones o umbral fuera de rango (400).
     */
    public Map<String, Object> updateSettings(Boolean acceptCanCodes, Double reviewMinScore) {
        if (acceptCanCodes == null && reviewMinScore == null) {
            throw new IllegalArgumentException("Indica qué opción cambiar (acceptCanCodes o reviewMinScore).");
        }
        if (reviewMinScore != null && (reviewMinScore.isNaN() || reviewMinScore < REVIEW_MIN_LOW
                || reviewMinScore > REVIEW_MIN_HIGH)) {
            throw new IllegalArgumentException("El parecido mínimo para revisar debe estar entre " + REVIEW_MIN_LOW
                    + " y " + REVIEW_MIN_HIGH + ".");
        }
        String actor = actor();
        write(() -> {
            List<String> changes = new ArrayList<>();
            if (acceptCanCodes != null && acceptCan() != acceptCanCodes) {
                setConfig(CFG_ACCEPT_CAN, Boolean.toString(acceptCanCodes),
                        "Aceptar NSO de otros paises de la Comunidad Andina: Colombia, Bolivia, Ecuador (true/false)");
                changes.add(acceptCanCodes
                        ? "Ahora cuentan los NSO de otros países de la Comunidad Andina (Colombia, Bolivia, Ecuador)"
                        : "Ya no cuentan los NSO de Colombia, Bolivia ni Ecuador: solo los de Perú");
            }
            if (reviewMinScore != null) {
                double v = Math.round(reviewMinScore * 100.0) / 100.0;
                double before = reviewMin();
                if (Double.compare(before, v) != 0) {
                    setConfig(CFG_REVIEW_MIN, String.valueOf(v),
                            "Parecido minimo (0.5 a 0.95) para proponer una NSO a revision");
                    changes.add("Parecido mínimo para proponer opciones a revisión: " + before + " → " + v);
                }
            }
            if (!changes.isEmpty()) {
                event(NsoEvent.TYPE_SETTINGS, null, null, null, null, actor, String.join("; ", changes));
                gate.invalidate();
                if (recordRepo.countByActiveTrue() > 0) requestRematchAll(actor, false);
            }
            return null;
        });
        return summary();
    }

    // =====================================================================
    // 4. Ganchos para otros servicios (merge, borrado, import)
    // =====================================================================

    /**
     * Corre un gancho NSO (onMerge, onProductDeleted, rematchProducts...) DESPUES del commit de la transaccion en
     * curso del llamador, para que vea sus datos confirmados y no se ejecute si hace rollback. Sin transaccion en
     * curso corre ya.
     *
     * Trampa de Spring: dentro de afterCommit la transaccion vieja sigue ligada al hilo y un REQUIRED se uniria a
     * ella SIN commit (las escrituras se perderian). Por eso el gancho corre en una transaccion NUEVA (REQUIRES_NEW)
     * con el lock tomado hasta su commit (igual garantia que write()). Un fallo del gancho no rompe la operacion del
     * llamador, que ya quedo confirmada: se registra y se sigue (el "Volver a verificar todo" lo corrige).
     */
    public void runAfterCommit(String what, Runnable hook) {
        if (hook == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runIsolated(what, hook);
                }
            });
        } else {
            runIsolated(what, hook);
        }
    }

    private void runIsolated(String what, Runnable hook) {
        lock.lock();
        try {
            requiresNewTx.executeWithoutResult(status -> hook.run());
        } catch (RuntimeException e) {
            System.err.println("[NSO] No se pudo completar '" + what + "': " + e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tras fusionar duplicateId en canonicalId (llamar con las ofertas ya re-apuntadas): la decision bloqueada del
     * duplicado pasa al canonico si este no tiene una; sus rechazos valen para el canonico; se limpian sus filas y
     * se re-verifica el canonico.
     */
    public void onMerge(Long canonicalId, Long duplicateId) {
        if (canonicalId == null || duplicateId == null || canonicalId.equals(duplicateId)) return;
        String actor = actor();
        write(() -> {
            ProductNso dup = productNsoRepo.findById(duplicateId).orElse(null);
            ProductNso can = productNsoRepo.findById(canonicalId).orElse(null);
            if (dup != null && Boolean.TRUE.equals(dup.getLocked()) && dup.getNsoCode() != null
                    && (can == null || !Boolean.TRUE.equals(can.getLocked()))) {
                if (can == null) can = new ProductNso(canonicalId, dup.getStatus());
                can.setStatus(dup.getStatus());
                can.setNsoCode(dup.getNsoCode());
                can.setMatchedBy(dup.getMatchedBy());
                can.setScore(dup.getScore());
                can.setReasonsJson(dup.getReasonsJson());
                can.setBrandKey(dup.getBrandKey());
                can.setLocked(true);
                can.setDecidedBy(dup.getDecidedBy());
                can.setDecidedAt(dup.getDecidedAt());
                can.setCheckedAt(LocalDateTime.now());
                productNsoRepo.save(can);
            }
            Map<String, NsoCandidate> canCands = new HashMap<>();
            for (NsoCandidate c : candidateRepo.findByProductIdOrderByRankAscIdAsc(canonicalId)) canCands.put(c.getNsoCode(), c);
            List<NsoCandidate> dupCands = candidateRepo.findByProductIdOrderByRankAscIdAsc(duplicateId);
            for (NsoCandidate c : dupCands) {
                if (!NsoCandidate.STATUS_REJECTED.equals(c.getStatus())) continue;
                NsoCandidate target = canCands.get(c.getNsoCode());
                if (target == null) {
                    target = new NsoCandidate(canonicalId, c.getNsoCode(), c.getScore(), c.getRank(), c.getOrigin());
                    target.setReasonsJson(c.getReasonsJson());
                } else if (!NsoCandidate.STATUS_PENDING.equals(target.getStatus())) {
                    continue;
                }
                target.setStatus(NsoCandidate.STATUS_REJECTED);
                target.setResolvedAt(c.getResolvedAt() != null ? c.getResolvedAt() : LocalDateTime.now());
                target.setResolvedBy(c.getResolvedBy());
                candidateRepo.save(target);
            }
            if (!dupCands.isEmpty()) candidateRepo.deleteAll(dupCands);
            if (dup != null) productNsoRepo.delete(dup);
            for (NsoAlias a : aliasRepo.findBySourceProductId(duplicateId)) {
                a.setSourceProductId(canonicalId);
                aliasRepo.save(a);
            }
            event(NsoEvent.TYPE_PRODUCT_MERGED, canonicalId, null, null, null, actor,
                    "Se fusionó el perfume #" + duplicateId + " en este");
            rematchInternal(List.of(canonicalId), actor, true);
            gate.invalidate();
            return null;
        });
    }

    /** Limpieza al borrar un producto (candidatos y estado; los alias se conservan sin producto de origen). */
    public void onProductDeleted(Long productId) {
        if (productId == null) return;
        String actor = actor();
        write(() -> {
            List<NsoCandidate> cands = candidateRepo.findByProductIdOrderByRankAscIdAsc(productId);
            if (!cands.isEmpty()) candidateRepo.deleteAll(cands);
            productNsoRepo.findById(productId).ifPresent(productNsoRepo::delete);
            for (NsoAlias a : aliasRepo.findBySourceProductId(productId)) {
                a.setSourceProductId(null);
                aliasRepo.save(a);
            }
            event(NsoEvent.TYPE_PRODUCT_DELETED, productId, null, null, null, actor, "Se borró el perfume #" + productId);
            gate.invalidate();
            return null;
        });
    }

    /** Preview de import (solo lectura): un veredicto por fila. */
    public PreviewResult matchPreviewRows(org.example.backendbvaberiaperfumes.model.Supplier supplier, List<ParsedRow> rows) {
        return matchPreviewRows(supplier, rows, null);
    }

    /**
     * Preview de import (solo lectura). existingProductIds (opcional, paralelo a rows, con nulls) = producto ya
     * existente de cada fila: si tiene una decision bloqueada vigente se muestra esa, y sus rechazos se respetan.
     * Sin catalogo cargado: catalogLoaded=false y veredictos con todo en null.
     */
    public PreviewResult matchPreviewRows(org.example.backendbvaberiaperfumes.model.Supplier supplier, List<ParsedRow> rows,
                                          List<Long> existingProductIds) {
        PreviewResult out = new PreviewResult();
        List<ParsedRow> list = rows == null ? List.of() : rows;
        return read(() -> {
            if (recordRepo.countByActiveTrue() == 0) {
                for (int i = 0; i < list.size(); i++) out.rows.add(new RowVerdict());
                return out;
            }
            out.catalogLoaded = true;
            String supplierName = supplier == null ? null : supplier.getName();
            List<NsoMatcher.Evidence> evidences = new ArrayList<>();
            for (ParsedRow row : list) {
                NsoMatcher.Evidence e = NsoMatcher.Evidence.of(null, row.brand, row.name).forma(row.forma).ml(row.ml)
                        .gtin(row.gtin);
                e.offer(supplierName, row.supplierSku, row.rawTitle, row.gtin);
                evidences.add(e);
            }
            List<Long> pids = new ArrayList<>();
            if (existingProductIds != null) for (Long id : existingProductIds) if (id != null) pids.add(id);
            Session s = openScopedSession(pids, evidences);
            Map<Long, ProductNso> states = statesOf(pids);
            for (int i = 0; i < list.size(); i++) {
                Long pid = existingProductIds != null && i < existingProductIds.size() ? existingProductIds.get(i) : null;
                NsoMatcher.Evidence e = evidences.get(i);
                ProductNso st = pid == null ? null : states.get(pid);
                NsoMatcher.Result r;
                if (st != null && Boolean.TRUE.equals(st.getLocked()) && st.getNsoCode() != null
                        && s.matcher.index().isActiveCode(st.getNsoCode())) {
                    e.productId = pid;
                    e.locked(st.getStatus(), st.getNsoCode(), st.getMatchedBy());
                } else if (pid != null) {
                    e.productId = pid;
                }
                r = s.matcher.resolve(e);
                RowVerdict v = verdictOf(s.matcher.index(), r);
                out.rows.add(v);
                switch (v.status == null ? "" : v.status) {
                    case ProductNso.STATUS_CON_NSO -> out.conNso++;
                    case ProductNso.STATUS_EN_REVISION -> out.review++;
                    case ProductNso.STATUS_MARCA_CON_NSO -> out.brandOnly++;
                    default -> out.none++;
                }
            }
            return out;
        });
    }

    private RowVerdict verdictOf(NsoMatcher.Index index, NsoMatcher.Result r) {
        RowVerdict v = new RowVerdict();
        v.status = r.status;
        v.reason = r.reasons.isEmpty() ? null : r.reasons.get(0);
        String code = null;
        if (r.isConNso()) code = r.nsoCode;
        else if (ProductNso.STATUS_EN_REVISION.equals(r.status) && !r.candidates.isEmpty()) code = r.candidates.get(0).code;
        if (code != null) {
            NsoRecord rec = index.record(code);
            v.nsoCode = code;
            v.country = countryOf(code, rec);
            if (rec != null) {
                v.declaredName = rec.getDeclaredName();
                v.titular = rec.getTitular();
            }
        } else if (ProductNso.STATUS_MARCA_CON_NSO.equals(r.status) && !r.brandTitulares.isEmpty()) {
            v.titular = r.brandTitulares.get(0).titular;
        }
        return v;
    }

    // =====================================================================
    // 5. Catalogo y alta manual
    // =====================================================================

    /** GET /catalog: {items:[NsoRecordView], total, page, size}. */
    public Map<String, Object> catalog(String q, String brand, int page, int size) {
        return read(() -> {
            Page<NsoRecord> p = recordRepo.searchCatalog(q, brand, page, size);
            Map<String, Long> linked = productNsoRepo.countLinkedByCode();
            List<Map<String, Object>> items = new ArrayList<>();
            for (NsoRecord r : p.getContent()) items.add(recordView(r, linked.getOrDefault(r.getCode(), 0L)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", items);
            out.put("total", p.getTotalElements());
            out.put("page", p.getNumber());
            out.put("size", p.getSize());
            return out;
        });
    }

    /**
     * Agrega un codigo a mano (source MANUAL) y re-verifica EN EL MOMENTO los perfumes no bloqueados de esa marca.
     * Respuesta {record, rematched, linkedProducts:[{id, brand, name, status}] (los que cambiaron de estado)}.
     * @throws IllegalArgumentException datos invalidos (400); NsoConflictException si el codigo ya existe (409).
     */
    public Map<String, Object> addCatalogRecord(NewRecord req) {
        if (req == null) throw new IllegalArgumentException("Faltan los datos del código.");
        String code = canonicalOrThrow(req.code);
        if (isBlank(req.brand) || isBlank(req.declaredName)) {
            throw new IllegalArgumentException("Escribe la marca y el nombre del producto como figura en la NSO.");
        }
        String actor = actor();
        return write(() -> {
            if (recordRepo.existsById(code)) {
                throw new NsoConflictException("El código " + code + " ya está en tu lista de NSO.");
            }
            NsoRecord r = createRecord(code, req.brand, req.declaredName, req.titular, req.ruc, actor);
            RematchOutcome o = rematchInternal(productIdsOfCatalogBrand(r.getBrandKey()), actor, true);
            Map<Long, Product> products = productsById(o.changes.stream().map(Change::productId).toList());
            List<Map<String, Object>> linked = new ArrayList<>();
            for (Change ch : o.changes) {
                if (!ch.statusOrCodeChanged()) continue;
                Product p = products.get(ch.productId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ch.productId());
                m.put("brand", p == null ? null : p.getBrand());
                m.put("name", p == null ? null : p.getName());
                m.put("status", ch.toStatus());
                linked.add(m);
            }
            long linkedCount = productNsoRepo.findByNsoCodeAndStatus(code, ProductNso.STATUS_CON_NSO).size();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("record", recordView(r, linkedCount));
            out.put("rematched", o.processed);
            out.put("linkedProducts", linked);
            return out;
        });
    }

    /**
     * Crea un perfume a mano CON su NSO en una sola transaccion (POST /api/admin/products). Valida el codigo ANTES
     * de crear nada; sin codigo, re-verifica el producto nuevo. Respuesta {product, nso:{productId, status, nsoCode}}.
     */
    public Map<String, Object> createProductWithNso(Product product, ManualCode nso) {
        if (product == null) throw new IllegalArgumentException("Faltan los datos del perfume.");
        String code = nso != null && !isBlank(nso.code) ? canonicalOrThrow(nso.code) : null;
        String actor = actor();
        return write(() -> {
            NsoRecord record = null;
            if (code != null) {
                record = recordRepo.findById(code).orElse(null);
                if (record == null && !Boolean.TRUE.equals(nso.createIfMissing)) {
                    throw new NsoNotFoundException("El código " + code + " no está en tu lista de NSO. ¿Quieres agregarlo?", true);
                }
                if (record != null && !Boolean.TRUE.equals(record.getActive())) {
                    throw new NsoConflictException("El código " + code + " está desactivado en tu lista. Actívalo primero.");
                }
            }
            if (isBlank(product.getSku())) throw new IllegalArgumentException("Falta el SKU del perfume.");
            if (isBlank(product.getBrand())) throw new IllegalArgumentException("Falta la marca del perfume.");
            if (isBlank(product.getName())) throw new IllegalArgumentException("Falta el nombre del perfume.");
            String sku = product.getSku().trim();
            if (productRepo.existsBySku(sku)) {
                throw new NsoConflictException("Ya existe un perfume con el SKU «" + sku + "».");
            }
            product.setId(null);
            product.setSku(sku);
            if (product.getArchived() == null) product.setArchived(false);
            if (product.getAvailable() == null) product.setAvailable(true);
            if (product.getForma() == null) product.setForma("single");
            Product saved = productRepo.save(product);

            Map<String, Object> nsoOut;
            if (code != null) {
                if (record == null) {
                    String declared = !isBlank(nso.declaredName) ? nso.declaredName : saved.getBrand() + " " + saved.getName();
                    record = createRecord(code, saved.getBrand(), declared, nso.titular, nso.ruc, actor);
                }
                String brandKey = decide(saved, record, ProductNso.MATCHED_MANUAL, NsoAlias.ORIGIN_MANUAL, actor, null,
                        List.of("asignado a mano al crear el perfume: «" + record.getDeclaredName() + "»"));
                event(NsoEvent.TYPE_MANUAL_ASSIGN, saved.getId(), code, null, ProductNso.STATUS_CON_NSO, actor,
                        "Perfume creado a mano con " + code);
                rematchBrand(brandKey, saved.getId(), actor);
                nsoOut = new LinkedHashMap<>();
                nsoOut.put("productId", saved.getId());
                nsoOut.put("status", ProductNso.STATUS_CON_NSO);
                nsoOut.put("nsoCode", code);
            } else {
                rematchInternal(List.of(saved.getId()), actor, true);
                nsoOut = statusResult(saved.getId());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("product", saved);
            out.put("nso", nsoOut);
            return out;
        });
    }

    // =====================================================================
    // 6. Lecturas del panel
    // =====================================================================

    /** GET /summary (contrato 2). */
    public Map<String, Object> summary() {
        return read(() -> {
            boolean acceptCan = acceptCan();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("gateEnabled", "true".equalsIgnoreCase(value(CFG_GATE, "false").trim()));
            out.put("gateEffective", gate.isActive());
            // nso_accept_can_codes: el frontend lo usa en isHidden() (codigos CO/BO/EC cuentan o no).
            out.put("acceptCanCodes", acceptCan);
            // FASE 3: umbral de revision (se cambia con PUT /settings).
            out.put("reviewMinScore", reviewMin());
            out.put("catalogRecords", recordRepo.count());
            out.put("catalogActiveRecords", recordRepo.countByActiveTrue());
            out.put("catalogVersion", intConfig(CFG_CATALOG_VERSION, 0));
            out.put("lastUpload", lastUpload());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String s : ProductNso.STATUSES) counts.put(s, 0L);
            for (Object[] row : productNsoRepo.countCurrentProductsByStatus()) {
                counts.merge((String) row[0], ((Number) row[1]).longValue(), Long::sum);
            }
            out.put("counts", counts);
            out.put("pendingCandidates", candidateRepo.countCurrentProductsWithPending());
            out.put("publicNow", productNsoRepo.countPublicNow());
            out.put("publicIfActivated", acceptCan ? productNsoRepo.countPublicIfActivated()
                    : productNsoRepo.countPublicIfActivatedPeru());
            Map<String, Long> linked = productNsoRepo.countLinkedByCode();
            List<Map<String, Object>> missing = new ArrayList<>();
            for (NsoRecord r : recordRepo.findByInLastUploadFalseOrderByBrandKeyAscCodeAsc()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", r.getCode());
                m.put("brand", r.getBrand());
                m.put("declaredName", r.getDeclaredName());
                m.put("linkedProducts", linked.getOrDefault(r.getCode(), 0L));
                missing.add(m);
            }
            out.put("codesNotInLastUpload", missing);
            out.put("rematch", rematchProgress());
            return out;
        });
    }

    /** GET /index (contrato 5): solo productos con fila. */
    public List<Map<String, Object>> index() {
        return read(() -> {
            List<ProductNso> states = new ArrayList<>(productNsoRepo.findAll());
            states.sort(Comparator.comparing(ProductNso::getProductId));
            Map<String, NsoRecord> records = recordsByCode(states.stream().map(ProductNso::getNsoCode).toList());
            int year = Year.now().getValue();
            List<Map<String, Object>> out = new ArrayList<>(states.size());
            for (ProductNso st : states) {
                String code = st.getNsoCode();
                NsoRecord rec = code == null ? null : records.get(code);
                Integer nsoYear = code == null ? null : yearOf(code, rec);
                String country = code == null ? null : countryOf(code, rec);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", st.getProductId());
                m.put("status", st.getStatus());
                m.put("nsoCode", code);
                m.put("matchedBy", st.getMatchedBy());
                m.put("locked", Boolean.TRUE.equals(st.getLocked()));
                m.put("score", st.getScore());
                m.put("country", country);
                m.put("nsoYear", nsoYear);
                m.put("otherCanCountry", country != null && !NsoMatcher.PERU.equals(country));
                m.put("possiblyExpired", NsoCode.possiblyExpired(nsoYear, year));
                out.add(m);
            }
            return out;
        });
    }

    /** GET /candidates/count: productos (no archivados) con candidatos PENDING. */
    public long pendingCount() {
        return read(candidateRepo::countCurrentProductsWithPending);
    }

    /** GET /review (contrato 7). */
    public List<Map<String, Object>> review() {
        return read(() -> {
            Map<Long, List<NsoCandidate>> byProduct = new LinkedHashMap<>();
            for (NsoCandidate c : candidateRepo.findByStatusOrderByProductIdAscRankAscIdAsc(NsoCandidate.STATUS_PENDING)) {
                byProduct.computeIfAbsent(c.getProductId(), k -> new ArrayList<>()).add(c);
            }
            Map<Long, Product> products = productsById(byProduct.keySet());
            Map<Long, List<SupplierOffer>> offers = offersOf(products.keySet());
            Map<Long, ProductNso> states = statesOf(products.keySet());
            Set<String> codes = new HashSet<>();
            byProduct.values().forEach(l -> l.forEach(c -> codes.add(c.getNsoCode())));
            Map<String, NsoRecord> records = recordsByCode(codes);
            int year = Year.now().getValue();

            List<Product> ordered = new ArrayList<>(products.values());
            ordered.sort(productOrder());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Product p : ordered) {
                Map<String, Object> product = new LinkedHashMap<>();
                product.put("id", p.getId());
                product.put("brand", p.getBrand());
                product.put("name", p.getName());
                product.put("ml", p.getMl());
                product.put("imageUrl", p.getImageUrl());
                product.put("gtin", p.getGtin());
                product.put("pricePen", pricePen(p));
                List<Map<String, Object>> offerViews = new ArrayList<>();
                for (SupplierOffer o : offers.getOrDefault(p.getId(), List.of())) {
                    Map<String, Object> ov = new LinkedHashMap<>();
                    ov.put("supplierName", o.getSupplier() == null ? null : o.getSupplier().getName());
                    ov.put("rawTitle", o.getRawTitle());
                    ov.put("supplierSku", o.getSupplierSku());
                    ov.put("inStock", Boolean.TRUE.equals(o.getInStock()));
                    offerViews.add(ov);
                }
                product.put("offers", offerViews);

                List<NsoCandidate> cands = new ArrayList<>(byProduct.get(p.getId()));
                cands.sort(Comparator.comparing((NsoCandidate c) -> c.getRank() == null ? Integer.MAX_VALUE : c.getRank())
                        .thenComparing(NsoCandidate::getId));
                List<Map<String, Object>> candViews = new ArrayList<>();
                for (NsoCandidate c : cands) {
                    NsoRecord rec = records.get(c.getNsoCode());
                    Map<String, Object> cv = new LinkedHashMap<>();
                    cv.put("id", c.getId());
                    cv.put("nsoCode", c.getNsoCode());
                    cv.put("brand", rec == null ? null : rec.getBrand());
                    cv.put("declaredName", rec == null ? null : rec.getDeclaredName());
                    cv.put("titular", rec == null ? null : rec.getTitular());
                    cv.put("ruc", rec == null ? null : rec.getRuc());
                    cv.put("country", countryOf(c.getNsoCode(), rec));
                    cv.put("nsoYear", yearOf(c.getNsoCode(), rec));
                    cv.put("score", c.getScore());
                    cv.put("reasons", parseList(c.getReasonsJson()));
                    cv.put("origin", c.getOrigin());
                    candViews.add(cv);
                }
                ProductNso st = states.get(p.getId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("product", product);
                item.put("reasons", st == null ? List.of() : parseList(st.getReasonsJson()));
                item.put("candidates", candViews);
                out.add(item);
            }
            return out;
        });
    }

    /** GET /brand-groups (contrato 12): productos MARCA_CON_NSO agrupados por marca, con titulares + RUC. */
    public List<Map<String, Object>> brandGroups() {
        return read(() -> {
            List<ProductNso> states = productNsoRepo.findCurrentByStatus(ProductNso.STATUS_MARCA_CON_NSO);
            Map<String, List<Long>> byBrand = new TreeMap<>();
            for (ProductNso st : states) byBrand.computeIfAbsent(nz(st.getBrandKey()), k -> new ArrayList<>()).add(st.getProductId());
            List<Long> allIds = states.stream().map(ProductNso::getProductId).toList();
            Map<Long, Product> products = productsById(allIds);
            Map<Long, List<SupplierOffer>> offers = offersOf(products.keySet());

            List<NsoRecord> active = recordRepo.findByActiveTrue();
            active.sort(Comparator.comparing(NsoRecord::getCode));
            NsoBrandDictionary dict = NsoBrandDictionary.build(active, aliasRepo.findByKind(NsoAlias.KIND_BRAND));
            Map<String, List<NsoRecord>> recordsByBrand = new HashMap<>();
            for (NsoRecord r : active) {
                String bk = dict.canonicalKey(r.getBrandKey() != null && !r.getBrandKey().isBlank() ? r.getBrandKey() : r.getBrand());
                if (byBrand.containsKey(bk)) recordsByBrand.computeIfAbsent(bk, k -> new ArrayList<>()).add(r);
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, List<Long>> e : byBrand.entrySet()) {
                String bk = e.getKey();
                List<NsoRecord> recs = recordsByBrand.getOrDefault(bk, List.of());
                Map<String, Map<String, Object>> titulares = new TreeMap<>();
                int generic = 0, unknown = 0;
                Set<String> terms = dict.brandTerms(bk);
                for (NsoRecord r : recs) {
                    if (NsoNormalizer.normalizeDeclared(r.getDeclaredName(), terms).isGeneric()) generic++;
                    String titular = r.getTitular();
                    String ruc = r.getRuc();
                    if (isBlank(titular)) {
                        unknown++;
                        titular = "titular desconocido (Aduanet)";
                        ruc = null;
                    }
                    String key = titular.trim() + "|" + (ruc == null ? "" : ruc.trim());
                    String t = titular.trim();
                    String rr = ruc == null ? null : ruc.trim();
                    @SuppressWarnings("unchecked")
                    List<String> codesList = (List<String>) titulares.computeIfAbsent(key, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("titular", t);
                        m.put("ruc", rr);
                        m.put("codes", new ArrayList<String>());
                        return m;
                    }).get("codes");
                    codesList.add(r.getCode());
                }
                List<Product> ps = new ArrayList<>();
                for (Long id : e.getValue()) if (products.containsKey(id)) ps.add(products.get(id));
                ps.sort(productOrder());
                List<Map<String, Object>> pviews = new ArrayList<>();
                for (Product p : ps) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("brand", p.getBrand());
                    m.put("name", p.getName());
                    m.put("ml", p.getMl());
                    m.put("pricePen", pricePen(p));
                    m.put("imageUrl", p.getImageUrl());
                    m.put("suppliers", supplierNames(offers.getOrDefault(p.getId(), List.of())));
                    pviews.add(m);
                }
                if (pviews.isEmpty()) continue;
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("brandKey", bk);
                g.put("brandName", recs.isEmpty() ? (ps.isEmpty() ? bk : ps.get(0).getBrand()) : dict.displayName(bk));
                g.put("titulares", new ArrayList<>(titulares.values()));
                g.put("genericRecords", generic);
                g.put("unknownTitularCodes", unknown);
                g.put("products", pviews);
                out.add(g);
            }
            out.sort(Comparator.comparing((Map<String, Object> g) -> -((List<?>) g.get("products")).size())
                    .thenComparing(g -> String.valueOf(g.get("brandName"))));
            return out;
        });
    }

    /** GET /products?status= (contrato 13). Solo productos no archivados. */
    public List<Map<String, Object>> productsByStatus(String status) {
        if (status == null || !ProductNso.STATUSES.contains(status)) {
            throw new IllegalArgumentException("Estado NSO desconocido: " + status);
        }
        return read(() -> {
            Map<Long, ProductNso> states = new HashMap<>();
            List<Product> products = new ArrayList<>();
            for (ProductNso st : productNsoRepo.findCurrentByStatus(status)) states.put(st.getProductId(), st);
            products.addAll(productsById(states.keySet()).values());
            if (ProductNso.STATUS_SIN_VERIFICAR.equals(status)) products.addAll(productNsoRepo.findCurrentProductsWithoutState());
            products.sort(productOrder());
            Map<Long, List<SupplierOffer>> offers = offersOf(products.stream().map(Product::getId).toList());
            Map<String, NsoRecord> records = recordsByCode(states.values().stream().map(ProductNso::getNsoCode).toList());
            NsoBrandDictionary dict = ProductNso.STATUS_SIN_NSO.equals(status)
                    ? NsoBrandDictionary.build(recordRepo.findByActiveTrue(), aliasRepo.findByKind(NsoAlias.KIND_BRAND)) : null;
            int year = Year.now().getValue();
            List<Map<String, Object>> out = new ArrayList<>(products.size());
            for (Product p : products) {
                ProductNso st = states.get(p.getId());
                String code = st == null ? null : st.getNsoCode();
                NsoRecord rec = code == null ? null : records.get(code);
                Integer nsoYear = code == null ? null : yearOf(code, rec);
                String suggested = null;
                if (dict != null) {
                    List<String> titles = new ArrayList<>();
                    if (p.getName() != null) titles.add(p.getName());
                    for (SupplierOffer o : offers.getOrDefault(p.getId(), List.of())) if (o.getRawTitle() != null) titles.add(o.getRawTitle());
                    NsoBrandDictionary.BrandMatch bm = dict.resolve(p.getBrand(), titles);
                    if (!bm.isResolved()) suggested = bm.suggestedBrand;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("brand", p.getBrand());
                m.put("name", p.getName());
                m.put("ml", p.getMl());
                m.put("imageUrl", p.getImageUrl());
                m.put("pricePen", pricePen(p));
                m.put("suppliers", supplierNames(offers.getOrDefault(p.getId(), List.of())));
                m.put("available", !Boolean.FALSE.equals(p.getAvailable()));
                m.put("archived", Boolean.TRUE.equals(p.getArchived()));
                m.put("status", st == null ? ProductNso.STATUS_SIN_VERIFICAR : st.getStatus());
                m.put("nsoCode", code);
                m.put("declaredName", rec == null ? null : rec.getDeclaredName());
                m.put("titular", rec == null ? null : rec.getTitular());
                m.put("matchedBy", st == null ? null : st.getMatchedBy());
                m.put("locked", st != null && Boolean.TRUE.equals(st.getLocked()));
                m.put("score", st == null ? null : st.getScore());
                m.put("reasons", st == null ? List.of() : parseList(st.getReasonsJson()));
                m.put("suggestedBrand", suggested);
                m.put("country", code == null ? null : countryOf(code, rec));
                m.put("nsoYear", nsoYear);
                m.put("possiblyExpired", NsoCode.possiblyExpired(nsoYear, year));
                m.put("decidedBy", st == null ? null : st.getDecidedBy());
                m.put("decidedAt", st == null ? null : st.getDecidedAt());
                out.add(m);
            }
            return out;
        });
    }

    /** GET /events (contrato 18). */
    public List<NsoEvent> events(Long productId, String type, int limit) {
        return read(() -> eventRepo.recent(productId, type, limit));
    }

    // =====================================================================
    // Sesiones del matcher (cargas masivas, una vez por operacion)
    // =====================================================================

    /** Indice + productos/ofertas cargados para una operacion. */
    private final class Session {
        final NsoMatcher matcher;
        final Map<Long, Product> products;
        final Map<Long, List<SupplierOffer>> offers;
        final int catalogVersion;

        Session(NsoMatcher matcher, Map<Long, Product> products, Map<Long, List<SupplierOffer>> offers, int catalogVersion) {
            this.matcher = matcher;
            this.products = products;
            this.offers = offers;
            this.catalogVersion = catalogVersion;
        }

        /** Evidencia NUEVA (mutable) del producto, sin bloqueo. */
        NsoMatcher.Evidence evidence(Long productId) {
            Product p = products.get(productId);
            return p == null ? null : evidenceOf(p, offers.getOrDefault(productId, List.of()));
        }
    }

    private record CatalogInputs(List<NsoRecord> records, List<NsoAlias> aliases, List<NsoAlias> brandAliases,
                                 Map<Long, Set<String>> rejected, boolean acceptCan, double reviewMin) {}

    private CatalogInputs loadInputs() {
        List<NsoRecord> records = recordRepo.findByActiveTrue();
        List<NsoAlias> aliases = aliasRepo.findAll();
        List<NsoAlias> brandAliases = new ArrayList<>();
        for (NsoAlias a : aliases) if (NsoAlias.KIND_BRAND.equals(a.getKind())) brandAliases.add(a);
        Map<Long, Set<String>> rejected = new HashMap<>();
        for (NsoCandidate c : candidateRepo.findByStatusOrderByProductIdAscRankAscIdAsc(NsoCandidate.STATUS_REJECTED)) {
            rejected.computeIfAbsent(c.getProductId(), k -> new TreeSet<>()).add(c.getNsoCode());
        }
        return new CatalogInputs(records, aliases, brandAliases, rejected, acceptCan(), reviewMin());
    }

    private NsoMatcher buildMatcher(CatalogInputs in, Collection<NsoMatcher.Evidence> siblings) {
        return new NsoMatcher(NsoMatcher.Index.builder()
                .records(in.records())
                .aliases(in.aliases())
                .rejectedPairs(in.rejected())
                .siblings(siblings)
                .acceptCanCodes(in.acceptCan())
                .reviewMinScore(in.reviewMin())
                .build());
    }

    /** Todo el catalogo vigente (rematch masivo). */
    private Session openFullSession() {
        CatalogInputs in = loadInputs();
        Map<Long, Product> products = new LinkedHashMap<>();
        for (Product p : productRepo.findByArchivedFalse()) products.put(p.getId(), p);
        Map<Long, List<SupplierOffer>> offers = groupOffers(offerRepo.findAllForNsoMatching());
        List<NsoMatcher.Evidence> siblings = new ArrayList<>(products.size());
        for (Product p : products.values()) siblings.add(evidenceOf(p, offers.getOrDefault(p.getId(), List.of())));
        return new Session(buildMatcher(in, siblings), products, offers, intConfig(CFG_CATALOG_VERSION, 0));
    }

    /**
     * Sesion acotada: los productos pedidos + sus hermanos de marca (ProductNso.brandKey) como siblings, y la
     * evidencia extra (filas de preview). Evita cargar el catalogo entero en cada click de la admin.
     */
    private Session openScopedSession(Collection<Long> targetIds, Collection<NsoMatcher.Evidence> extra) {
        CatalogInputs in = loadInputs();
        NsoBrandDictionary dict = NsoBrandDictionary.build(in.records(), in.brandAliases());
        Map<Long, Product> products = productsById(targetIds);
        Map<Long, List<SupplierOffer>> offers = new HashMap<>(offersOf(products.keySet()));
        Set<String> brandKeys = new TreeSet<>();
        for (Product p : products.values()) brandKeys.add(brandOf(dict, evidenceOf(p, offers.getOrDefault(p.getId(), List.of()))));
        for (NsoMatcher.Evidence e : extra) brandKeys.add(brandOf(dict, e));
        brandKeys.remove("");
        if (!brandKeys.isEmpty()) {
            List<Long> siblingIds = new ArrayList<>();
            for (Long id : productNsoRepo.findProductIdsByBrandKeyIn(brandKeys)) if (!products.containsKey(id)) siblingIds.add(id);
            Map<Long, Product> sib = productsById(siblingIds);
            offers.putAll(offersOf(sib.keySet()));
            products.putAll(sib);
        }
        List<NsoMatcher.Evidence> siblings = new ArrayList<>(products.size() + extra.size());
        for (Product p : products.values()) siblings.add(evidenceOf(p, offers.getOrDefault(p.getId(), List.of())));
        siblings.addAll(extra);
        return new Session(buildMatcher(in, siblings), products, offers, intConfig(CFG_CATALOG_VERSION, 0));
    }

    /** Marca canonica como la calcula el matcher (NsoMatcher.prepare). */
    private static String brandOf(NsoBrandDictionary dict, NsoMatcher.Evidence e) {
        List<String> texts = new ArrayList<>();
        if (e.name != null && !e.name.isBlank()) texts.add(e.name);
        for (NsoMatcher.Offer o : e.offers) if (o.rawTitle != null && !o.rawTitle.isBlank()) texts.add(o.rawTitle);
        NsoBrandDictionary.BrandMatch m = dict.resolve(e.brand, texts);
        if (m.isResolved()) return m.brandKey;
        return NsoKeys.brandKey(e.brand).isEmpty() ? "" : dict.canonicalKey(e.brand);
    }

    private static NsoMatcher.Evidence evidenceOf(Product p, List<SupplierOffer> offers) {
        NsoMatcher.Evidence e = NsoMatcher.Evidence.of(p.getId(), p.getBrand(), p.getName())
                .type(p.getType()).category(p.getCategory()).forma(p.getForma()).ml(p.getMl()).gtin(p.getGtin());
        for (SupplierOffer o : offers) {
            e.offer(o.getSupplier() == null ? null : o.getSupplier().getName(), o.getSupplierSku(), o.getRawTitle(), o.getGtin());
        }
        return e;
    }

    /**
     * Perfumes NO bloqueados cuya marca (resuelta con el diccionario actual, por marca + nombre) es la de ese
     * registro, mas las filas guardadas con esa marca. Para "agregue un codigo / una marca: re-verificalos ya".
     */
    private List<Long> productIdsOfCatalogBrand(String recordBrand) {
        NsoBrandDictionary dict = NsoBrandDictionary.build(recordRepo.findByActiveTrue(), aliasRepo.findByKind(NsoAlias.KIND_BRAND));
        String canonical = dict.canonicalKey(recordBrand);
        if (canonical == null || canonical.isEmpty()) return List.of();
        Set<Long> locked = new HashSet<>();
        for (ProductNso st : productNsoRepo.findByLockedTrue()) locked.add(st.getProductId());
        Set<Long> ids = new LinkedHashSet<>();
        for (Product p : productRepo.findByArchivedFalse()) {
            if (locked.contains(p.getId())) continue;
            NsoBrandDictionary.BrandMatch m = dict.resolve(p.getBrand(), p.getName() == null ? List.of() : List.of(p.getName()));
            String bk = m.isResolved() ? m.brandKey : dict.canonicalKey(p.getBrand());
            if (canonical.equals(bk)) ids.add(p.getId());
        }
        for (String key : dict.catalogKeysOf(canonical)) {
            for (ProductNso st : productNsoRepo.findByBrandKeyAndLockedFalse(key)) ids.add(st.getProductId());
        }
        for (ProductNso st : productNsoRepo.findByBrandKeyAndLockedFalse(canonical)) ids.add(st.getProductId());
        return new ArrayList<>(ids);
    }

    /** Re-verifica los productos no bloqueados de una marca (excepto uno). */
    private RematchOutcome rematchBrand(String brandKey, Long exclude, String actor) {
        if (brandKey == null || brandKey.isBlank()) return new RematchOutcome();
        List<Long> ids = new ArrayList<>();
        for (ProductNso st : productNsoRepo.findByBrandKeyAndLockedFalse(brandKey)) {
            if (!st.getProductId().equals(exclude)) ids.add(st.getProductId());
        }
        return rematchInternal(ids, actor, true);
    }

    // =====================================================================
    // Decision bloqueada + alias
    // =====================================================================

    private static final class AliasKeys {
        final Set<String> gtins = new TreeSet<>();
        final Set<String> skus = new TreeSet<>();
        final Set<String> nameKeys = new LinkedHashSet<>();
        String brandKey;
    }

    private AliasKeys aliasKeys(Session s, Long productId) {
        AliasKeys k = new AliasKeys();
        Product p = s.products.get(productId);
        if (p == null) return k;
        NsoMatcher.Evidence e = s.evidence(productId);
        String g = NsoKeys.gtinKey(p.getGtin());
        if (g != null) k.gtins.add(g);
        for (SupplierOffer o : s.offers.getOrDefault(productId, List.of())) {
            String og = NsoKeys.gtinKey(o.getGtin());
            if (og != null) k.gtins.add(og);
            String sk = NsoKeys.skuKey(o.getSupplier() == null ? null : o.getSupplier().getName(), o.getSupplierSku());
            if (sk != null) k.skus.add(sk);
        }
        k.nameKeys.addAll(s.matcher.nameKeys(e));
        k.brandKey = s.matcher.resolve(e).brandKey;
        return k;
    }

    /**
     * Fija CON_NSO bloqueado con ese codigo, marca el candidato del codigo ACCEPTED y los demas PENDING SUPERSEDED,
     * y guarda los alias POSITIVE. Devuelve la marca canonica del producto.
     */
    private String decide(Product product, NsoRecord record, String matchedBy, String aliasOrigin, String actor,
                          Double score, List<String> reasons) {
        Long pid = product.getId();
        String code = record.getCode();
        Session s = openScopedSession(List.of(pid), List.of());
        AliasKeys keys = aliasKeys(s, pid);
        LocalDateTime now = LocalDateTime.now();

        ProductNso state = productNsoRepo.findById(pid).orElse(null);
        if (state == null) state = new ProductNso(pid, ProductNso.STATUS_CON_NSO);
        state.setStatus(ProductNso.STATUS_CON_NSO);
        state.setNsoCode(code);
        state.setMatchedBy(matchedBy);
        state.setScore(score);
        state.setReasonsJson(toJsonList(reasons));
        state.setBrandKey(keys.brandKey);
        state.setLocked(true);
        state.setDecidedBy(actor);
        state.setDecidedAt(now);
        state.setCheckedAt(now);
        state.setCatalogVersion(intConfig(CFG_CATALOG_VERSION, 0));
        productNsoRepo.save(state);

        for (NsoCandidate c : candidateRepo.findByProductIdOrderByRankAscIdAsc(pid)) {
            if (code.equals(c.getNsoCode())) {
                if (!NsoCandidate.STATUS_ACCEPTED.equals(c.getStatus())) {
                    c.setStatus(NsoCandidate.STATUS_ACCEPTED);
                    c.setResolvedAt(now);
                    c.setResolvedBy(actor);
                    candidateRepo.save(c);
                }
            } else if (NsoCandidate.STATUS_PENDING.equals(c.getStatus())) {
                c.setStatus(NsoCandidate.STATUS_SUPERSEDED);
                c.setResolvedAt(now);
                c.setResolvedBy(actor);
                candidateRepo.save(c);
            }
        }
        for (String k : keys.gtins) upsertAlias(NsoAlias.KIND_GTIN, k, code, NsoAlias.POSITIVE, aliasOrigin, pid, actor);
        for (String k : keys.skus) upsertAlias(NsoAlias.KIND_SUPPLIER_SKU, k, code, NsoAlias.POSITIVE, aliasOrigin, pid, actor);
        for (String k : keys.nameKeys) upsertAlias(NsoAlias.KIND_NAME_KEY, k, code, NsoAlias.POSITIVE, aliasOrigin, pid, actor);
        gate.invalidate();
        return keys.brandKey;
    }

    /** Inserta o actualiza el alias de la clave unica (kind, aliasKey, codigo): cambiar de opinion actualiza la fila. */
    private void upsertAlias(String kind, String key, String code, String polarity, String origin, Long productId, String actor) {
        if (key == null || key.isBlank() || key.length() > ALIAS_KEY_MAX) return;
        NsoAlias a = aliasRepo.findUnique(kind, key, code).orElse(null);
        if (a == null) {
            a = new NsoAlias(kind, key, code, polarity, origin);
        } else if (polarity.equals(a.getPolarity()) && origin.equals(a.getOrigin())
                && Objects.equals(productId, a.getSourceProductId())) {
            return;
        }
        a.setPolarity(polarity);
        a.setOrigin(origin);
        a.setSourceProductId(productId);
        a.setCreatedBy(actor);
        aliasRepo.save(a);
    }

    /** Codigo existente y activo, o creado MANUAL si la admin lo pidio. */
    private NsoRecord ensureRecord(String code, ManualCode req, Product product, String actor) {
        NsoRecord r = recordRepo.findById(code).orElse(null);
        if (r != null) {
            if (!Boolean.TRUE.equals(r.getActive())) {
                throw new NsoConflictException("El código " + code + " está desactivado en tu lista. Actívalo primero en Catálogo.");
            }
            return r;
        }
        if (req == null || !Boolean.TRUE.equals(req.createIfMissing)) {
            throw new NsoNotFoundException("El código " + code + " no está en tu lista de NSO. ¿Quieres agregarlo?", true);
        }
        String brand = !isBlank(req.brand) ? req.brand : product.getBrand();
        String declared = !isBlank(req.declaredName) ? req.declaredName : product.getBrand() + " " + product.getName();
        return createRecord(code, brand, declared, req.titular, req.ruc, actor);
    }

    private NsoRecord createRecord(String code, String brand, String declaredName, String titular, String ruc, String actor) {
        if (isBlank(brand) || isBlank(declaredName)) {
            throw new IllegalArgumentException("Escribe la marca y el nombre del producto como figura en la NSO.");
        }
        String b = brand.trim();
        String d = declaredName.trim();
        if (b.length() > 150) throw new IllegalArgumentException("La marca es demasiado larga.");
        if (d.length() > 500) throw new IllegalArgumentException("El nombre declarado es demasiado largo.");
        String t = isBlank(titular) ? null : titular.trim();
        if (t != null && t.length() > 300) throw new IllegalArgumentException("El nombre del titular es demasiado largo.");
        String rr = isBlank(ruc) ? null : ruc.trim();
        if (rr != null && !rr.matches("\\d{8,20}")) throw new IllegalArgumentException("El RUC debe tener solo números (11 dígitos).");
        NsoRecord r = new NsoRecord(code, b, NsoKeys.brandKey(b), d);
        r.setTitular(t);
        r.setRuc(rr);
        r.setCountry(NsoCode.country(code));
        r.setNsoYear(NsoCode.year(code));
        r.setSource(NsoRecord.SOURCE_MANUAL);
        r.setActive(true);
        r.setInLastUpload(true);
        recordRepo.save(r);
        bumpVersion();
        event(NsoEvent.TYPE_CODE_CREATED, null, code, null, null, actor, "Agregaste " + code + " «" + d + "» (" + b + ")");
        gate.invalidate();
        return r;
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    private <T> T write(Supplier<T> body) {
        lock.lock();
        try {
            return tx.execute(status -> body.get());
        } finally {
            lock.unlock();
        }
    }

    private <T> T read(Supplier<T> body) {
        return readTx.execute(status -> body.get());
    }

    private String actor() {
        try {
            Admin a = currentAdmin.current();
            if (a != null && a.getEmail() != null) return a.getEmail();
        } catch (RuntimeException ignored) {
            // sin sesion (hilo de fondo): sistema
        }
        return SYSTEM_ACTOR;
    }

    private Product liveProduct(Long productId) {
        Product p = productId == null ? null : productRepo.findById(productId).orElse(null);
        if (p == null || Boolean.TRUE.equals(p.getArchived())) {
            throw new NsoNotFoundException("Ese perfume ya no existe.");
        }
        return p;
    }

    private Map<String, Object> statusResult(Long productId) {
        ProductNso st = productNsoRepo.findById(productId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productId", productId);
        out.put("status", st == null ? ProductNso.STATUS_SIN_VERIFICAR : st.getStatus());
        out.put("nsoCode", st == null ? null : st.getNsoCode());
        return out;
    }

    private static String canonicalOrThrow(String raw) {
        if (isBlank(raw)) throw new IllegalArgumentException("Escribe el código NSO (ej. NSOC70523-25PE).");
        String code = NsoCode.canonicalize(raw);
        if (code == null) {
            throw new IllegalArgumentException("«" + raw.trim() + "» no tiene el formato de un código NSO (ej. NSOC70523-25PE).");
        }
        return code;
    }

    private static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null) return List.of();
        TreeSet<Long> set = new TreeSet<>();
        for (Long id : ids) if (id != null) set.add(id);
        return new ArrayList<>(set);
    }

    /** Productos NO archivados por id (en bloques). */
    private Map<Long, Product> productsById(Collection<Long> ids) {
        Map<Long, Product> out = new LinkedHashMap<>();
        List<Long> list = distinctIds(ids);
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            for (Product p : productRepo.findAllById(list.subList(i, Math.min(list.size(), i + IN_CHUNK)))) {
                if (!Boolean.TRUE.equals(p.getArchived())) out.put(p.getId(), p);
            }
        }
        return out;
    }

    private Map<Long, List<SupplierOffer>> offersOf(Collection<Long> productIds) {
        List<Long> list = distinctIds(productIds);
        List<SupplierOffer> all = new ArrayList<>();
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            all.addAll(offerRepo.findForNsoByProductIds(list.subList(i, Math.min(list.size(), i + IN_CHUNK))));
        }
        return groupOffers(all);
    }

    private static Map<Long, List<SupplierOffer>> groupOffers(List<SupplierOffer> offers) {
        Map<Long, List<SupplierOffer>> out = new HashMap<>();
        for (SupplierOffer o : offers) {
            if (o.getProduct() == null) continue;
            out.computeIfAbsent(o.getProduct().getId(), k -> new ArrayList<>()).add(o);
        }
        for (List<SupplierOffer> l : out.values()) {
            l.sort(Comparator.comparing(SupplierOffer::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return out;
    }

    private Map<Long, ProductNso> statesOf(Collection<Long> productIds) {
        List<Long> list = distinctIds(productIds);
        Map<Long, ProductNso> out = new HashMap<>();
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            for (ProductNso st : productNsoRepo.findByProductIdIn(list.subList(i, Math.min(list.size(), i + IN_CHUNK)))) {
                out.put(st.getProductId(), st);
            }
        }
        return out;
    }

    private Map<Long, List<NsoCandidate>> candidatesOf(Collection<Long> productIds) {
        List<Long> list = distinctIds(productIds);
        Map<Long, List<NsoCandidate>> out = new HashMap<>();
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            for (NsoCandidate c : candidateRepo.findByProductIdIn(list.subList(i, Math.min(list.size(), i + IN_CHUNK)))) {
                out.computeIfAbsent(c.getProductId(), k -> new ArrayList<>()).add(c);
            }
        }
        return out;
    }

    private Map<String, NsoRecord> recordsByCode(Collection<String> codes) {
        TreeSet<String> set = new TreeSet<>();
        for (String c : codes) if (c != null) set.add(c);
        List<String> list = new ArrayList<>(set);
        Map<String, NsoRecord> out = new HashMap<>();
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            for (NsoRecord r : recordRepo.findByCodeIn(list.subList(i, Math.min(list.size(), i + IN_CHUNK)))) out.put(r.getCode(), r);
        }
        return out;
    }

    private Map<String, Object> recordView(NsoRecord r, long linkedProducts) {
        Integer nsoYear = yearOf(r.getCode(), r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("brand", r.getBrand());
        m.put("declaredName", r.getDeclaredName());
        m.put("titular", r.getTitular());
        m.put("ruc", r.getRuc());
        m.put("tipo", r.getTipo());
        m.put("origen", r.getOrigen());
        m.put("country", countryOf(r.getCode(), r));
        m.put("nsoYear", nsoYear);
        m.put("source", r.getSource());
        m.put("active", Boolean.TRUE.equals(r.getActive()));
        m.put("inLastUpload", !Boolean.FALSE.equals(r.getInLastUpload()));
        m.put("linkedProducts", linkedProducts);
        m.put("possiblyExpired", NsoCode.possiblyExpired(nsoYear, Year.now().getValue()));
        m.put("usdKg", r.getUsdKg());
        m.put("lastImportDate", r.getLastImportDate());
        return m;
    }

    private Map<String, Object> lastUpload() {
        String at = configValueOrNull(CFG_LAST_UPLOAD_AT);
        if (at != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at);
            m.put("by", configValueOrNull(CFG_LAST_UPLOAD_BY));
            m.put("filename", configValueOrNull(CFG_LAST_UPLOAD_FILENAME));
            return m;
        }
        return eventRepo.findFirstByTypeOrderByCreatedAtDescIdDesc(NsoEvent.TYPE_CATALOG_UPLOAD).map(ev -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", ev.getCreatedAt());
            m.put("by", ev.getActor());
            m.put("filename", null);
            return m;
        }).orElse(null);
    }

    private static String countryOf(String code, NsoRecord rec) {
        if (rec != null && !isBlank(rec.getCountry())) return rec.getCountry().trim();
        return NsoCode.country(code);
    }

    private static Integer yearOf(String code, NsoRecord rec) {
        if (rec != null && rec.getNsoYear() != null) return rec.getNsoYear();
        return NsoCode.year(code);
    }

    /** Precio que se muestra en el panel: el de encargo (wholesale) o, si no hay, el de tienda. */
    private static Double pricePen(Product p) {
        return p.getWholesalePricePen() != null ? p.getWholesalePricePen() : p.getRetailPricePen();
    }

    private static List<String> supplierNames(List<SupplierOffer> offers) {
        Set<String> names = new TreeSet<>();
        for (SupplierOffer o : offers) if (o.getSupplier() != null && o.getSupplier().getName() != null) names.add(o.getSupplier().getName());
        return new ArrayList<>(names);
    }

    private static Comparator<Product> productOrder() {
        return Comparator.comparing((Product p) -> nz(p.getBrand()).toLowerCase())
                .thenComparing(p -> nz(p.getName()).toLowerCase())
                .thenComparing(Product::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** Separador de aliasIdentity (caracter de control "unit separator", U+001F): no aparece en claves reales. */
    private static final String ID_SEP = "\u001F";

    private static String aliasIdentity(NsoAlias a) {
        return a.getKind() + ID_SEP + a.getAliasKey() + ID_SEP + NsoAlias.codeKeyOf(a.getNsoCode());
    }

    /** JSON de una lista de textos que cabe en la columna (se descartan los ultimos motivos si no entra). */
    private String toJsonList(List<String> values) {
        List<String> list = new ArrayList<>(values == null ? List.of() : values);
        try {
            String s = json.writeValueAsString(list);
            while (s.length() > JSON_MAX && !list.isEmpty()) {
                list.remove(list.size() - 1);
                s = json.writeValueAsString(list);
            }
            return s;
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private void event(String type, Long productId, String code, String from, String to, String actor, String detail) {
        eventRepo.save(new NsoEvent(type, productId, code, from, to, actor, detail));
    }

    private int bumpVersion() {
        int v = intConfig(CFG_CATALOG_VERSION, 0) + 1;
        setConfig(CFG_CATALOG_VERSION, String.valueOf(v), "Version de la lista de NSO cargada (sube en cada carga; no editar a mano)");
        return v;
    }

    private boolean acceptCan() {
        return !"false".equalsIgnoreCase(value(CFG_ACCEPT_CAN, "true").trim());
    }

    private double reviewMin() {
        try {
            return Double.parseDouble(value(CFG_REVIEW_MIN, String.valueOf(NsoMatcher.DEFAULT_REVIEW_MIN_SCORE)).trim());
        } catch (NumberFormatException e) {
            return NsoMatcher.DEFAULT_REVIEW_MIN_SCORE;
        }
    }

    private String value(String key, String fallback) {
        String v = configValueOrNull(key);
        return v == null ? fallback : v;
    }

    private String configValueOrNull(String key) {
        return configRepo.findByConfigKey(key).map(AppConfig::getConfigValue).filter(v -> !v.isBlank()).orElse(null);
    }

    private int intConfig(String key, int fallback) {
        try {
            return Integer.parseInt(value(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void setConfig(String key, String value, String description) {
        AppConfig c = configRepo.findByConfigKey(key).orElse(null);
        if (c == null) {
            configRepo.save(new AppConfig(key, value, description));
        } else if (!Objects.equals(c.getConfigValue(), value)) {
            c.setConfigValue(value);
            configRepo.save(c);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
