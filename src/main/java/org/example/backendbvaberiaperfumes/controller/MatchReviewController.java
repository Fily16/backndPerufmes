package org.example.backendbvaberiaperfumes.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.MatchCandidate;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.MatchCandidateRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.service.DuplicateScanService;
import org.example.backendbvaberiaperfumes.service.ProductMergeService;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Cola de revision de duplicados/typos y fusion manual de productos.
 * Todo bajo /api/admin (JWT). El sistema PROPONE; el admin DECIDE.
 */
@RestController
@RequestMapping("/api/admin")
public class MatchReviewController {

    private final MatchCandidateRepository candidateRepo;
    private final ProductRepository productRepo;
    private final SupplierOfferRepository offerRepo;
    private final ProductMergeService mergeService;
    private final DuplicateScanService scanService;
    private final ObjectMapper json = new ObjectMapper();

    public MatchReviewController(MatchCandidateRepository candidateRepo,
                                 ProductRepository productRepo,
                                 SupplierOfferRepository offerRepo,
                                 ProductMergeService mergeService,
                                 DuplicateScanService scanService) {
        this.candidateRepo = candidateRepo;
        this.productRepo = productRepo;
        this.offerRepo = offerRepo;
        this.mergeService = mergeService;
        this.scanService = scanService;
    }

    // =====================================================================
    // Cola de revision
    // =====================================================================

    @GetMapping("/match-candidates")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "PENDING") String status,
                                          @RequestParam(required = false) String kind) {
        List<MatchCandidate> items = (kind == null || kind.isBlank())
                ? candidateRepo.findByStatusOrderByCreatedAtDesc(status)
                : candidateRepo.findByStatusAndKindOrderByCreatedAtDesc(status, kind);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MatchCandidate mc : items) {
            // Auto-limpieza: si un producto del par ya no existe (borrado despues de encolar),
            // el candidato es huerfano y se resuelve solo en vez de mostrarse roto.
            if ("PENDING".equals(mc.getStatus()) && isOrphan(mc)) {
                mc.setStatus("REJECTED");
                mc.setResolvedAt(LocalDateTime.now());
                mc.setResolvedBy("auto-huerfano");
                candidateRepo.save(mc);
                continue;
            }
            out.add(view(mc));
        }
        return out;
    }

    private boolean isOrphan(MatchCandidate mc) {
        if (mc.getSourceProductId() != null && productRepo.findById(mc.getSourceProductId()).isEmpty()) return true;
        return mc.getTargetProductId() != null && productRepo.findById(mc.getTargetProductId()).isEmpty();
    }

    /**
     * Vaciar la cola: elimina TODOS los candidatos pendientes (sin marcar rechazo, asi
     * un re-escaneo puede volver a proponerlos) y limpia el flag "en revision" de los
     * productos involucrados. Para empezar limpio despues de borrar proveedores/imports.
     */
    @DeleteMapping("/match-candidates/pending")
    public Map<String, Object> clearPending() {
        List<MatchCandidate> pending = candidateRepo.findByStatusOrderByCreatedAtDesc("PENDING");
        int cleared = 0;
        for (MatchCandidate mc : pending) {
            if (mc.getSourceProductId() != null) {
                productRepo.findById(mc.getSourceProductId()).ifPresent(p -> {
                    if (Boolean.TRUE.equals(p.getMatchPending())) {
                        p.setMatchPending(false);
                        productRepo.save(p);
                    }
                });
            }
            candidateRepo.delete(mc);
            cleared++;
        }
        return Map.of("cleared", cleared, "pending", candidateRepo.countByStatus("PENDING"));
    }

    @GetMapping("/match-candidates/count")
    public Map<String, Object> pendingCount() {
        return Map.of("pending", candidateRepo.countByStatus("PENDING"));
    }

    /** Acepta el candidato: fusiona source dentro de target (o marca visto si no hay fusion). */
    @PostMapping("/match-candidates/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable Long id, Authentication auth) {
        MatchCandidate mc = candidateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidato no existe: " + id));
        if (!"PENDING".equals(mc.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "El candidato ya fue resuelto"));
        }
        Object result = Map.of("acknowledged", true);
        if (mc.getSourceProductId() != null && mc.getTargetProductId() != null) {
            // La fusion tambien resuelve este candidato, pero lo dejamos explicito.
            result = mergeService.merge(mc.getTargetProductId(), mc.getSourceProductId());
        }
        mc.setStatus("ACCEPTED");
        mc.setResolvedAt(LocalDateTime.now());
        mc.setResolvedBy(auth != null ? auth.getName() : "admin");
        candidateRepo.save(mc);
        return ResponseEntity.ok(result);
    }

    /** Rechaza el candidato: NO son el mismo producto. El par no se vuelve a proponer. */
    @PostMapping("/match-candidates/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id, Authentication auth) {
        MatchCandidate mc = candidateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidato no existe: " + id));
        if (!"PENDING".equals(mc.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "El candidato ya fue resuelto"));
        }
        mc.setStatus("REJECTED");
        mc.setResolvedAt(LocalDateTime.now());
        mc.setResolvedBy(auth != null ? auth.getName() : "admin");
        candidateRepo.save(mc);

        // Si el producto provisional ya no tiene candidatos pendientes, deja de estar "en revision".
        if (mc.getSourceProductId() != null
                && candidateRepo.findBySourceProductIdAndStatus(mc.getSourceProductId(), "PENDING").isEmpty()) {
            productRepo.findById(mc.getSourceProductId()).ifPresent(p -> {
                p.setMatchPending(false);
                productRepo.save(p);
            });
        }
        return ResponseEntity.ok(Map.of("rejected", true));
    }

    // =====================================================================
    // Fusion manual y escaneo de duplicados
    // =====================================================================

    /** Fusion directa elegida por el admin (ej. desde la ficha del producto). */
    @PostMapping("/products/{canonicalId}/merge/{duplicateId}")
    public ResponseEntity<?> merge(@PathVariable Long canonicalId, @PathVariable Long duplicateId) {
        return ResponseEntity.ok(mergeService.merge(canonicalId, duplicateId));
    }

    /**
     * Fija (o limpia) el UPC/GTIN de un producto a mano, con GUARD de conflicto: si el codigo
     * ya pertenece a OTRO producto vivo, no lo pisa a ciegas -> responde 409 con el producto en
     * conflicto para que el admin elija fusionar (POST .../merge/...) o descartar. Cierra el caso
     * "un admin escribe un UPC que ya es de otro producto" sin crear duplicados silenciosos.
     */
    @PutMapping("/products/{id}/gtin")
    public ResponseEntity<?> setGtin(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Product p = productRepo.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();

        String raw = body != null ? body.get("gtin") : null;
        if (raw == null || raw.isBlank()) {
            // Limpiar el UPC es valido (deja el producto sin codigo).
            p.setGtin(null);
            p.setGtinConflict(false);
            productRepo.save(p);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("id", id);
            ok.put("gtin", null);
            return ResponseEntity.ok(ok);
        }

        GtinCanonicalizer.GtinResult gr = GtinCanonicalizer.canonicalize(raw);
        if (gr.canonical14 == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "UPC invalido (" + gr.status.name() + "). Revisa el codigo de barras.",
                    "status", gr.status.name()));
        }
        String canon = gr.canonical14;

        for (Product other : productRepo.findByGtinAndArchivedFalse(canon)) {
            if (!other.getId().equals(id)) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("message", "El UPC " + canon + " ya pertenece a otro producto ("
                        + other.getBrand() + " " + other.getName() + "). Fusiona ambos o revisa antes de asignarlo.");
                conflict.put("conflict", true);
                conflict.put("thisProductId", id);
                conflict.put("conflictProductId", other.getId());
                conflict.put("conflictBrand", other.getBrand());
                conflict.put("conflictName", other.getName());
                conflict.put("gtin", canon);
                return ResponseEntity.status(409).body(conflict);
            }
        }

        p.setGtin(canon);
        p.setGtinConflict(false);
        productRepo.save(p);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("id", id);
        ok.put("gtin", canon);
        return ResponseEntity.ok(ok);
    }

    /** Escanea el catalogo completo y puebla la cola de revision. */
    @PostMapping("/duplicates/scan")
    public Map<String, Object> scan() {
        int created = scanService.scan();
        return Map.of("candidatesCreated", created,
                "pending", candidateRepo.countByStatus("PENDING"));
    }

    // =====================================================================

    private Map<String, Object> view(MatchCandidate mc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mc.getId());
        m.put("kind", mc.getKind());
        m.put("score", mc.getScore());
        m.put("status", mc.getStatus());
        m.put("createdAt", mc.getCreatedAt());
        try {
            m.put("reasons", json.readValue(
                    mc.getReasonsJson() == null ? "[]" : mc.getReasonsJson(), List.class));
        } catch (Exception e) {
            m.put("reasons", List.of());
        }
        m.put("source", productBrief(mc.getSourceProductId()));
        m.put("target", productBrief(mc.getTargetProductId()));
        if (mc.getSupplierOfferId() != null) {
            offerRepo.findById(mc.getSupplierOfferId()).ifPresent(o -> m.put("offer", offerBrief(o)));
        }
        return m;
    }

    private Map<String, Object> productBrief(Long id) {
        if (id == null) return null;
        Optional<Product> op = productRepo.findById(id);
        if (op.isEmpty()) return null;
        Product p = op.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("sku", p.getSku());
        m.put("brand", p.getBrand());
        m.put("name", p.getName());
        m.put("ml", p.getMl());
        m.put("gtin", p.getGtin());
        m.put("imageUrl", p.getImageUrl());
        m.put("pricePen", p.getWholesalePricePen());
        m.put("archived", p.getArchived());
        m.put("mergedIntoId", p.getMergedIntoId());
        return m;
    }

    private Map<String, Object> offerBrief(SupplierOffer o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("supplierName", o.getSupplier() != null ? o.getSupplier().getName() : null);
        m.put("rawTitle", o.getRawTitle());
        m.put("costUsd", o.getCostUsd());
        m.put("gtinRaw", o.getGtinRaw());
        m.put("gtinStatus", o.getGtinStatus());
        return m;
    }
}
