package org.example.backendbvaberiaperfumes.service.nso;

import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claves normalizadas del sistema NSO (puro, sin Spring ni BD). Toda clave que se guarda en
 * nso_records.brand_key o nso_aliases.alias_key sale de aqui, para que el parser del catalogo,
 * el matcher y el servicio hablen EXACTAMENTE el mismo idioma.
 *
 *  - brandKey("Dolce & Gabbana")          -> "dolce and gabbana"
 *  - supplierKey("Oasis Perfumes")        -> "oasis"   (primera palabra significativa)
 *  - supplierKey("ZIMAXX INC")            -> "zimaxx"
 *  - skuKey("Oasis", " perf-afna-26 ")    -> "oasis|PERF-AFNA-26"
 *  - gtinKey("6290171000976")             -> "06290171000976" (GTIN-14 validado) o null
 *  - nameKey("afnan", [gala, supremacy], "parfum", "women", "perfume")
 *                                         -> "afnan|gala supremacy|parfum|women|perfume"
 */
public final class NsoKeys {

    private NsoKeys() {}

    /** Separador de las claves compuestas (SUPPLIER_SKU y NAME_KEY). */
    public static final String SEP = "|";

    /**
     * Palabras que NO identifican a un proveedor cuando van al inicio del nombre
     * ("The Perfume Spot", "Perfumes Oasis", "La Casa"): se saltan para tomar la primera significativa.
     */
    private static final Set<String> SUPPLIER_NOISE = Set.of(
            "the", "la", "el", "los", "las", "le", "les", "de", "del", "di", "and", "y",
            "perfume", "perfumes", "perfumeria", "parfum", "parfums", "fragrance", "fragrances",
            "inc", "llc", "sac", "srl", "corp", "co", "ltd", "sa"
    );

    private static final Pattern DATE_DMY = Pattern.compile("^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})$");
    private static final Pattern DATE_YMD = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ].*)?$");

    /** Texto plegado: sin acentos, minusculas, & -> and, solo [a-z0-9] y espacios colapsados. */
    public static String fold(String s) {
        if (s == null) return "";
        String t = PerfumeNormalizer.stripAccents(s).toLowerCase(Locale.ROOT)
                .replace("&", " and ");
        t = t.replaceAll("[^a-z0-9]+", " ").trim();
        return t.replaceAll("\\s+", " ");
    }

    /**
     * Clave de marca: marca plegada (sin acentos, minusculas, & -> and, espacios colapsados).
     * Es idempotente: brandKey(brandKey(x)) == brandKey(x). Null/vacio -> "".
     */
    public static String brandKey(String brand) {
        return fold(brand);
    }

    /** Marca sin espacios, para comparar "MONT BLANC" con "MONTBLANC". */
    public static String brandCompact(String brand) {
        return fold(brand).replace(" ", "");
    }

    /**
     * Clave de proveedor: minusculas sin acentos, solo [a-z0-9], y la PRIMERA palabra significativa.
     * Asi "Oasis" == "Oasis Perfumes" y "Zimaxx" == "ZIMAXX INC". Si todas las palabras son ruido
     * se usa el nombre completo compactado. Null/vacio -> "".
     */
    public static String supplierKey(String supplierName) {
        String f = fold(supplierName).replace(" and ", " ");
        if (f.isEmpty()) return "";
        for (String w : f.split(" ")) {
            if (!w.isEmpty() && !SUPPLIER_NOISE.contains(w)) return w;
        }
        return f.replace(" ", "");
    }

    /** SKU normalizado para claves: sin espacios en los bordes, espacios internos colapsados, MAYUSCULAS. */
    public static String normalizeSku(String sku) {
        if (sku == null) return "";
        return sku.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** Clave de alias SUPPLIER_SKU: "proveedorNormalizado|SKU". Null si falta proveedor o SKU. */
    public static String skuKey(String supplierName, String sku) {
        String s = supplierKey(supplierName);
        String k = normalizeSku(sku);
        if (s.isEmpty() || k.isEmpty()) return null;
        return s + SEP + k;
    }

    /** Clave de alias GTIN: GTIN-14 canonico SOLO si valida checksum GS1; si no, null. */
    public static String gtinKey(Object rawCode) {
        return GtinCanonicalizer.canonicalize(rawCode).canonical14;
    }

    /**
     * Clave de alias NAME_KEY: "brandKey|core ordenado|concentracion|genero|forma" (sin ml ni tester),
     * para que una aprobacion cubra los otros tamanos del mismo perfume pero no la version EDT/otro genero.
     * Los tokens del core se ordenan y se unen con un espacio; los nulos quedan como "".
     */
    public static String nameKey(String brandKey, Collection<String> coreTokens,
                                 String concentration, String gender, String forma) {
        String core = coreTokens == null ? "" : String.join(" ", coreTokens.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .sorted()
                .toList());
        return nz(brandKey(brandKey)) + SEP + core + SEP + nz(concentration) + SEP + nz(gender) + SEP + nz(forma);
    }

    /**
     * Normaliza una fecha de "ultima importacion" a DD/MM/YYYY. Acepta D/M/YYYY, D-M-YYYY y YYYY-MM-DD.
     * Devuelve null si no se reconoce o no es una fecha real.
     */
    public static String normalizeDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int d, m, y;
        Matcher dm = DATE_DMY.matcher(s);
        Matcher ym = DATE_YMD.matcher(s);
        if (dm.matches()) {
            d = Integer.parseInt(dm.group(1));
            m = Integer.parseInt(dm.group(2));
            y = Integer.parseInt(dm.group(3));
        } else if (ym.matches()) {
            y = Integer.parseInt(ym.group(1));
            m = Integer.parseInt(ym.group(2));
            d = Integer.parseInt(ym.group(3));
        } else {
            return null;
        }
        try {
            java.time.LocalDate.of(y, m, d);
        } catch (Exception e) {
            return null;
        }
        return String.format("%02d/%02d/%04d", d, m, y);
    }

    /** Clave ordenable yyyymmdd de una fecha DD/MM/YYYY (para "ultima importacion mas nueva"); 0 si no hay. */
    public static int dateSortKey(String ddmmyyyy) {
        String n = normalizeDate(ddmmyyyy);
        if (n == null) return 0;
        return Integer.parseInt(n.substring(6, 10)) * 10000
                + Integer.parseInt(n.substring(3, 5)) * 100
                + Integer.parseInt(n.substring(0, 2));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
