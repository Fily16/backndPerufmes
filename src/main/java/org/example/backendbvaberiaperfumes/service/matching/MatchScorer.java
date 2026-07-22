package org.example.backendbvaberiaperfumes.service.matching;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compara dos huellas y decide: AUTO_MATCH (mismo perfume, seguro),
 * REVIEW (probable, que lo confirme el admin) o NEW (distinto).
 *
 * Disenado contra los errores verificados en los Excel reales:
 *  - Trampa de flankers: "Yara" vs "Yara Candy" comparten casi todos los tokens
 *    pero son perfumes DISTINTOS -> cualquier token residual impide AUTO.
 *  - Tester vs regular: presentaciones distintas -> jamas se cruzan.
 *  - "Devotion Men" vs "Devotion Women": el genero es puerta dura.
 *  - 3.4 Oz vs 100ML: tolerancia de ml por conversion de onzas.
 */
public final class MatchScorer {

    private MatchScorer() {}

    public enum Decision { AUTO_MATCH, REVIEW, NEW }

    public static class Result {
        public final Decision decision;
        public final double score;       // 0..1 (jaccard de coreTokens, informativo)
        public final List<String> reasons;

        Result(Decision d, double score, List<String> reasons) {
            this.decision = d; this.score = score; this.reasons = reasons;
        }
    }

    /** Umbral por defecto de jaccard para proponer REVIEW (configurable via app_config). */
    public static final double DEFAULT_REVIEW_JACCARD = 0.6;

    public static Result score(ProductFingerprint a, ProductFingerprint b, double reviewJaccard) {
        List<String> reasons = new ArrayList<>();

        // ============ PUERTAS DURAS (fallo -> NEW) ============

        if (!brandCompatible(a.brandTokens, b.brandTokens)) {
            return new Result(Decision.NEW, 0, List.of("marca distinta: " + a.brandTokens + " vs " + b.brandTokens));
        }

        String presA = a.presentation == null ? "regular" : a.presentation;
        String presB = b.presentation == null ? "regular" : b.presentation;
        if (!presA.equals(presB)) {
            return new Result(Decision.NEW, 0, List.of("presentacion distinta: " + presA + " vs " + presB));
        }

        boolean capReview = false; // datos incompletos: como maximo REVIEW, nunca AUTO

        if (a.ml != null && b.ml != null) {
            int tol = (int) Math.max(3, Math.max(a.ml, b.ml) * 0.03);
            if (Math.abs(a.ml - b.ml) > tol) {
                return new Result(Decision.NEW, 0, List.of("tamano distinto: " + a.ml + "ml vs " + b.ml + "ml"));
            }
        } else {
            capReview = true;
            reasons.add("ml desconocido en un lado");
        }

        if (a.concentration != null && b.concentration != null) {
            if (!a.concentration.equals(b.concentration)) {
                return new Result(Decision.NEW, 0,
                        List.of("concentracion distinta: " + a.concentration + " vs " + b.concentration));
            }
        } else {
            capReview = true;
            reasons.add("concentracion desconocida en un lado");
        }

        if (a.gender != null && b.gender != null) {
            if (!a.gender.equals(b.gender)) {
                // Solo MEN vs WOMEN es contradiccion dura (Devotion Men != Devotion Women).
                // unisex vs men/women NO lo es: los proveedores etiquetan el MISMO perfume de
                // formas distintas (uno "Unisex", otro "Men"). Se degrada a REVIEW, no a NEW.
                boolean opposite = ("men".equals(a.gender) && "women".equals(b.gender))
                        || ("women".equals(a.gender) && "men".equals(b.gender));
                if (opposite) {
                    return new Result(Decision.NEW, 0,
                            List.of("genero distinto: " + a.gender + " vs " + b.gender));
                }
                capReview = true;
                reasons.add("genero ambiguo: " + a.gender + " vs " + b.gender + " (etiquetado inconsistente)");
            }
        } else {
            capReview = true;
            reasons.add("genero desconocido en un lado");
        }

        // ============ REGLA ANTI-FLANKER ============
        // Cualquier token que este en un lado y no en el otro puede ser un flanker
        // ("candy", "dukhan", "zanzibar"): impide el match automatico.

        Set<String> residualA = minus(a.coreTokens, b.coreTokens);
        Set<String> residualB = minus(b.coreTokens, a.coreTokens);
        if (!residualA.isEmpty() || !residualB.isEmpty()) {
            capReview = true;
            reasons.add("tokens residuales: " + residualA + " / " + residualB + " (posible flanker)");
        }

        // Nombres vacios (solo marca): nunca AUTO.
        if (a.coreTokens.isEmpty() && b.coreTokens.isEmpty()) {
            capReview = true;
            reasons.add("nombres sin tokens distintivos");
        }

        double jac = jaccard(a.coreTokens, b.coreTokens);

        // ============ DECISION ============

        if (!capReview && jac >= 0.9999) {
            return new Result(Decision.AUTO_MATCH, 1.0, List.of("identidad exacta de nombre y atributos"));
        }
        boolean residualOk = residualA.size() <= 1 && residualB.size() <= 1;
        if (jac >= reviewJaccard && residualOk) {
            return new Result(Decision.REVIEW, jac, reasons);
        }
        // Igualdad total de tokens pero con atributos incompletos tambien merece revision.
        if (jac >= 0.9999 && capReview) {
            return new Result(Decision.REVIEW, jac, reasons);
        }
        reasons.add("similitud insuficiente (jaccard=" + String.format(java.util.Locale.ROOT, "%.2f", jac) + ")");
        return new Result(Decision.NEW, jac, reasons);
    }

    /** Marcas compatibles: mismos tokens o un set contenido en el otro ("Carolina" vs "Carolina Herrera"). */
    static boolean brandCompatible(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.containsAll(b) || b.containsAll(a);
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
