package org.example.backendbvaberiaperfumes.service.nso;

/**
 * Utilidades de texto del reconocimiento NSO (puro, sin dependencias).
 *  - Jaro-Winkler: similitud [0..1] para ordenar candidatos y sugerir marcas parecidas.
 *  - Distancia de edicion <= 1: tolerar un typo del proveedor o de aduanas (TUBBES/TUBBEES, ROUGUE/ROUGE).
 * Nada de esto decide un NSO automatico por si solo: solo ordena o habilita una coincidencia de palabra.
 */
public final class NsoText {

    private NsoText() {}

    /** Similitud Jaro-Winkler clasica (prefijo hasta 4, p = 0.1). Null se trata como "". */
    public static double jaroWinkler(String a, String b) {
        String s1 = a == null ? "" : a;
        String s2 = b == null ? "" : b;
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        double jaro = jaro(s1, s2);
        int prefix = 0;
        int max = Math.min(4, Math.min(s1.length(), s2.length()));
        while (prefix < max && s1.charAt(prefix) == s2.charAt(prefix)) prefix++;
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    private static double jaro(String s1, String s2) {
        int l1 = s1.length(), l2 = s2.length();
        int window = Math.max(0, Math.max(l1, l2) / 2 - 1);
        boolean[] m1 = new boolean[l1];
        boolean[] m2 = new boolean[l2];
        int matches = 0;
        for (int i = 0; i < l1; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(l2 - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (m2[j] || s1.charAt(i) != s2.charAt(j)) continue;
                m1[i] = true;
                m2[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < l1; i++) {
            if (!m1[i]) continue;
            while (!m2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }
        double m = matches;
        return (m / l1 + m / l2 + (m - transpositions / 2.0) / m) / 3.0;
    }

    /**
     * Distancia de edicion (Levenshtein) EXACTAMENTE 1: una sustitucion, una insercion o una eliminacion.
     * Dos textos identicos devuelven false (no son un typo). Mismo patron que FingerprintExtractor.
     */
    public static boolean editDistanceIsOne(String a, String b) {
        if (a == null || b == null) return false;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        if (la > lb) { String ts = a; a = b; b = ts; int ti = la; la = lb; lb = ti; } // a = mas corto
        if (la == lb) {
            int diff = 0;
            for (int i = 0; i < la; i++) {
                if (a.charAt(i) != b.charAt(i) && ++diff > 1) return false;
            }
            return diff == 1;
        }
        // lb = la + 1: una sola insercion en b.
        int i = 0, j = 0;
        boolean skipped = false;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; }
            else {
                if (skipped) return false;
                skipped = true;
                j++;
            }
        }
        return true;
    }

    /** Distancia de edicion <= 1 (identicos incluidos). */
    public static boolean withinEditDistance1(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || editDistanceIsOne(a, b);
    }

    /** Distancia de edicion (Levenshtein) completa. Null se trata como "". */
    public static int levenshtein(String a, String b) {
        String s = a == null ? "" : a;
        String t = b == null ? "" : b;
        int[] prev = new int[t.length() + 1];
        int[] cur = new int[t.length() + 1];
        for (int j = 0; j <= t.length(); j++) prev[j] = j;
        for (int i = 1; i <= s.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= t.length(); j++) {
                int cost = s.charAt(i - 1) == t.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[t.length()];
    }

    /** Coeficiente de Dice sobre conteos: 2m / (n1 + n2). 0 si ambos lados estan vacios. */
    public static double dice(int matchedLeft, int matchedRight, int sizeLeft, int sizeRight) {
        int total = sizeLeft + sizeRight;
        if (total == 0) return 0.0;
        return (double) (matchedLeft + matchedRight) / total;
    }

    /** Redondeo a 2 decimales (los puntajes que ve la admin). */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
