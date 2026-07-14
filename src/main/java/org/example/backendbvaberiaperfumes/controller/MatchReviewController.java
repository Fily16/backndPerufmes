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
            out.add(view(mc));
        }
        return out;
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
