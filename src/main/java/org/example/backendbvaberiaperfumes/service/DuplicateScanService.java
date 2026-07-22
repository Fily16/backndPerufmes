package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.MatchCandidate;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.MatchCandidateRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.service.matching.FingerprintExtractor;
import org.example.backendbvaberiaperfumes.service.matching.MatchScorer;
import org.example.backendbvaberiaperfumes.service.matching.MatchingEngine;
import org.example.backendbvaberiaperfumes.service.matching.ProductFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Escanea el catalogo completo buscando pares de productos que parecen el MISMO
 * perfume (los duplicados historicos: seed sin GTIN vs importados con GTIN).
 * Solo puebla la cola de revision (MatchCandidate kind=DEDUP_SCAN); JAMAS fusiona:
 * la fusion la aprueba el admin uno por uno.
 */
@Service
public class DuplicateScanService {

    private final ProductRepository productRepo;
    private final MatchCandidateRepository candidateRepo;
    private final MatchingEngine engine;
    private final ObjectMapper json = new ObjectMapper();

    public DuplicateScanService(ProductRepository productRepo,
                                MatchCandidateRepository candidateRepo,
                                MatchingEngine engine) {
        this.productRepo = productRepo;
        this.candidateRepo = candidateRepo;
        this.engine = engine;
    }

    /** Recorre el catalogo y crea candidatos pendientes. Devuelve cuantos creo. */
    @Transactional
    public int scan() {
        double reviewJaccard = engine.reviewJaccard();

        List<Product> products = new ArrayList<>();
        Map<Long, ProductFingerprint> fps = new HashMap<>();
        Map<String, List<Long>> brandIndex = new HashMap<>();
        for (Product p : productRepo.findAll()) {
            if (Boolean.TRUE.equals(p.getArchived()) || p.getMergedIntoId() != null) continue;
            ProductFingerprint fp = FingerprintExtractor.fromProduct(p);
            products.add(p);
            fps.put(p.getId(), fp);
            for (String tok : fp.brandTokens) {
                brandIndex.computeIfAbsent(tok, k -> new ArrayList<>()).add(p.getId());
            }
        }
        Map<Long, Product> byId = new HashMap<>();
        for (Product p : products) byId.put(p.getId(), p);

        int created = 0;
        Set<String> seenPairs = new HashSet<>();
        for (Product a : products) {
            ProductFingerprint fa = fps.get(a.getId());
            Set<Long> candidateIds = new LinkedHashSet<>();
            for (String tok : fa.brandTokens) {
                List<Long> bucket = brandIndex.get(tok);
                if (bucket != null) candidateIds.addAll(bucket);
            }
            for (Long otherId : candidateIds) {
                if (otherId.equals(a.getId())) continue;
                // Cada par una sola vez.
                long lo = Math.min(a.getId(), otherId), hi = Math.max(a.getId(), otherId);
                if (!seenPairs.add(lo + "-" + hi)) continue;

                Product b = byId.get(otherId);
                boolean differentGtin = a.getGtin() != null && b.getGtin() != null
                        && !a.getGtin().equals(b.getGtin());

                MatchScorer.Result r = MatchScorer.score(fa, fps.get(otherId), reviewJaccard);
                if (r.decision == MatchScorer.Decision.NEW) continue;
                // Codigos DISTINTOS: en este rubro el MISMO perfume llega con barcodes distintos
                // segun el proveedor/region. Ya no se descarta de plano; se propone (nunca se fusiona
                // solo) SOLO si el nombre es practicamente identico (score ~1.0), para no meter ruido.
                if (differentGtin && r.score < 0.9999) continue;

                // Direccion: el canonico (target) es el que tiene GTIN; a igualdad, el mas antiguo.
                Product target = pickCanonical(a, b);
                Product source = target == a ? b : a;
                if (candidateRepo.existsBySourceProductIdAndTargetProductId(source.getId(), target.getId())
                        || candidateRepo.existsBySourceProductIdAndTargetProductId(target.getId(), source.getId())) {
                    continue; // ya propuesto (incluye rechazados: lista negra)
                }
                List<String> reasons = new ArrayList<>(r.reasons != null ? r.reasons : List.of());
                if (differentGtin) {
                    reasons.add(0, "codigos distintos (" + a.getGtin() + " vs " + b.getGtin()
                            + "): mismo nombre exacto -> posible mismo perfume de dos fuentes");
                }
                MatchCandidate mc = new MatchCandidate();
                mc.setKind("DEDUP_SCAN");
                mc.setSourceProductId(source.getId());
                mc.setTargetProductId(target.getId());
                mc.setScore(r.score);
                mc.setReasonsJson(toJson(reasons));
                candidateRepo.save(mc);
                created++;
            }
        }
        return created;
    }

    private Product pickCanonical(Product a, Product b) {
        boolean ag = a.getGtin() != null && !a.getGtin().isBlank();
        boolean bg = b.getGtin() != null && !b.getGtin().isBlank();
        if (ag && !bg) return a;
        if (bg && !ag) return b;
        return a.getId() < b.getId() ? a : b;
    }

    private String toJson(List<String> reasons) {
        try {
            String s = json.writeValueAsString(reasons);
            return s.length() > 1990 ? s.substring(0, 1990) : s;
        } catch (Exception e) {
            return "[]";
        }
    }
}
