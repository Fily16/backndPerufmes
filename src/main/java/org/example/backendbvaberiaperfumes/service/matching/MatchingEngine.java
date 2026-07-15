package org.example.backendbvaberiaperfumes.service.matching;

import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Nivel 2 del matching: resuelve filas SIN GTIN confiable contra el catalogo
 * por huella de nombre (marca + tokens + tamano + concentracion + genero +
 * presentacion). Nunca usa LIKE; el indice se arma en memoria por token de marca.
 *
 * Uso: abrir una Session por import (carga el catalogo una vez) y resolver
 * cada fila contra ella. Los productos creados durante el commit se agregan a
 * la sesion para que filas posteriores del mismo archivo los encuentren.
 */
@Service
public class MatchingEngine {

    private final ProductRepository productRepo;
    private final AppConfigRepository configRepo;

    public MatchingEngine(ProductRepository productRepo, AppConfigRepository configRepo) {
        this.productRepo = productRepo;
        this.configRepo = configRepo;
    }

    public double reviewJaccard() {
        return configRepo.findByConfigKey("match_review_jaccard")
                .map(c -> {
                    try { return Double.parseDouble(c.getConfigValue()); }
                    catch (NumberFormatException e) { return MatchScorer.DEFAULT_REVIEW_JACCARD; }
                })
                .orElse(MatchScorer.DEFAULT_REVIEW_JACCARD);
    }

    public Session openSession() {
        Session s = new Session(reviewJaccard());
        for (Product p : productRepo.findAll()) {
            if (Boolean.TRUE.equals(p.getArchived())) {
                // Los archivados NO participan del matching por nombre, pero SI se indexan
                // por GTIN: el cruce L1 debe comportarse igual que findByGtin (que no
                // distingue archivados) para no crear un producto nuevo con GTIN duplicado.
                s.indexGtinOnly(p);
                continue;
            }
            s.addProduct(p);
        }
        return s;
    }

    // =====================================================================

    public static class ReviewCandidate {
        public final Product product;
        public final double score;
        public final List<String> reasons;
        public final String kind; // L2_IMPORT | GTIN_TYPO

        ReviewCandidate(Product product, double score, List<String> reasons, String kind) {
            this.product = product; this.score = score; this.reasons = reasons; this.kind = kind;
        }
    }

    public static class MatchOutcome {
        /** Producto al que colgar la oferta directamente (identidad segura). */
        public Product autoMatch;
        /** Candidatos probables para la cola de revision (max 3, mejor primero). */
        public List<ReviewCandidate> reviewCandidates = new ArrayList<>();
    }

    public static class Session {
        private final double reviewJaccard;
        /** token de marca -> productos cuyo brand contiene ese token. */
        private final Map<String, List<Long>> brandIndex = new HashMap<>();
        private final Map<Long, Product> products = new HashMap<>();
        private final Map<Long, ProductFingerprint> fingerprints = new HashMap<>();
        /** GTIN-14 valido -> producto (para deteccion de typos). */
        private final Map<String, Product> gtinIndex = new HashMap<>();

        Session(double reviewJaccard) {
            this.reviewJaccard = reviewJaccard;
        }

        /** Solo indexa el GTIN (productos archivados: cuentan para L1, no para L2). */
        public void indexGtinOnly(Product p) {
            if (p.getGtin() != null && !p.getGtin().isBlank()) {
                // putIfAbsent: si un producto VIVO comparte el GTIN, el vivo debe ganar
                // sin importar el orden de carga (addProduct usa put y lo pisa).
                gtinIndex.putIfAbsent(p.getGtin(), p);
            }
        }

        /** Agrega (o re-indexa) un producto; usar tambien para los creados durante el commit. */
        public void addProduct(Product p) {
            if (p.getId() == null) return;
            products.put(p.getId(), p);
            ProductFingerprint fp = FingerprintExtractor.fromProduct(p);
            fingerprints.put(p.getId(), fp);
            for (String tok : fp.brandTokens) {
                brandIndex.computeIfAbsent(tok, k -> new ArrayList<>()).add(p.getId());
            }
            if (p.getGtin() != null && !p.getGtin().isBlank()) {
                gtinIndex.put(p.getGtin(), p);
            }
        }

        /** Resuelve una fila sin GTIN confiable contra el catalogo en memoria. */
        public MatchOutcome resolve(ParsedRow row) {
            MatchOutcome out = new MatchOutcome();
            ProductFingerprint fp = FingerprintExtractor.fromRow(row);

            // Candidatos: productos que comparten al menos un token de marca.
            Set<Long> candidateIds = new LinkedHashSet<>();
            for (String tok : fp.brandTokens) {
                List<Long> bucket = brandIndex.get(tok);
                if (bucket != null) candidateIds.addAll(bucket);
            }

            List<ReviewCandidate> autos = new ArrayList<>();
            List<ReviewCandidate> reviews = new ArrayList<>();
            for (Long id : candidateIds) {
                ProductFingerprint pf = fingerprints.get(id);
                if (pf == null) continue;
                MatchScorer.Result r = MatchScorer.score(fp, pf, reviewJaccard);
                if (r.decision == MatchScorer.Decision.AUTO_MATCH) {
                    autos.add(new ReviewCandidate(products.get(id), r.score, r.reasons, "L2_IMPORT"));
                } else if (r.decision == MatchScorer.Decision.REVIEW) {
                    reviews.add(new ReviewCandidate(products.get(id), r.score, r.reasons, "L2_IMPORT"));
                }
            }

            if (autos.size() == 1) {
                out.autoMatch = autos.get(0).product;
            } else if (autos.size() > 1) {
                // Mas de un match "seguro" = catalogo ya tiene duplicados -> que decida el admin.
                reviews.addAll(0, autos);
            }

            // Typo de GTIN: codigo con checksum invalido a 1 digito de un GTIN del catalogo.
            if ("CHECKSUM_FAIL".equals(row.gtinStatus) && row.gtinRaw != null) {
                for (Product p : GtinTypoDetector.suggest(row.gtinRaw, gtinIndex)) {
                    if (out.autoMatch != null && out.autoMatch.getId().equals(p.getId())) continue;
                    ProductFingerprint pf = fingerprints.get(p.getId());
                    MatchScorer.Result r = pf != null ? MatchScorer.score(fp, pf, reviewJaccard) : null;
                    if (r != null && r.decision != MatchScorer.Decision.NEW) {
                        List<String> reasons = new ArrayList<>();
                        reasons.add("codigo " + row.gtinRaw + " a 1 typo del GTIN " + p.getGtin());
                        reasons.addAll(r.reasons);
                        // Va primero: es la senal mas fuerte.
                        reviews.add(0, new ReviewCandidate(p, Math.max(r.score, 0.9), reasons, "GTIN_TYPO"));
                    }
                }
            }

            reviews.sort((x, y) -> Double.compare(y.score, x.score));
            // Dedupe por producto conservando el primero (mejor/typo).
            Set<Long> seen = new HashSet<>();
            for (ReviewCandidate rc : reviews) {
                if (rc.product == null || !seen.add(rc.product.getId())) continue;
                out.reviewCandidates.add(rc);
                if (out.reviewCandidates.size() >= 3) break;
            }
            return out;
        }

        /** Huella de un producto ya indexado (para chequeos de atributos en L1). */
        public ProductFingerprint fingerprintOf(Long productId) {
            return fingerprints.get(productId);
        }

        /**
         * Busqueda L1 por GTIN contra el indice EN MEMORIA (incluye los productos creados
         * durante este mismo commit). Evita una consulta a la BD por cada fila del Excel.
         */
        public Product byGtin(String gtin) {
            return gtin == null ? null : gtinIndex.get(gtin);
        }
    }
}
