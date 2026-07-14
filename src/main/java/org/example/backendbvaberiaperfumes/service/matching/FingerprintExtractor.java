package org.example.backendbvaberiaperfumes.service.matching;

import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Construye la huella (ProductFingerprint) de una fila de proveedor o de un Product.
 *
 * Reglas clave (aprendidas de los Excel reales):
 *  - Genero y concentracion se extraen ANTES de limpiar stopwords ("Devotion Men" vs
 *    "Devotion Women" solo difieren en un token que el limpiador borraria).
 *  - "women" se prueba antes que "men" porque WOMEN contiene MEN.
 *  - Tester es una presentacion propia: tiene UPC legitimo distinto al regular y
 *    NUNCA debe fusionarse con el.
 *  - Los numeros del tamano se quitan de los coreTokens, pero los numeros que son
 *    parte del nombre ("212", "9pm", "L.12.12") se conservan.
 */
public final class FingerprintExtractor {

    private FingerprintExtractor() {}

    // Genero: women ANTES que men (contencion), incluye variantes es/fr.
    private static final Pattern WOMEN = Pattern.compile("\\b(women|woman|femme|dama|mujer|ladies|her|w)\\b");
    private static final Pattern MEN = Pattern.compile("\\b(men|man|homme|caballero|hombre|him|m)\\b");
    private static final Pattern UNISEX = Pattern.compile("\\b(unisex|u)\\b");

    private static final Pattern TESTER = Pattern.compile("\\b(tester|tstr)\\b");
    private static final Pattern MINI = Pattern.compile("\\b(mini|miniature|miniatura)\\b");

    /**
     * Descriptores que no distinguen un perfume de otro (ademas de los de PerfumeNormalizer).
     * OJO: "elixir" NO esta aqui a proposito — es nombre de flanker (Le Male Elixir).
     */
    private static final Set<String> DESCRIPTOR_TOKENS = Set.of(
            "edp", "edt", "edc", "parfum", "extrait",
            "perfume", "eau", "de", "toilette", "cologne", "spray", "splash",
            "for", "women", "woman", "men", "man", "unisex", "oz", "ml", "hb",
            "pour", "homme", "femme", "the", "by", "new", "tester", "tstr",
            "set", "gift", "kit", "coffret", "pcs", "psc", "pc", "piece", "pieces",
            "sealed", "original", "brand", "travel", "size", "w", "m", "u"
    );

    /** Correcciones de typos de marca vistos en los Excel (GABANNA -> GABBANA, etc.). */
    private static final Map<String, String> BRAND_TOKEN_FIXES = Map.of(
            "gabanna", "gabbana",
            "dolce&gabbana", "gabbana",
            "d&g", "gabbana",
            "ck", "calvin",
            "jpg", "gaultier"
    );

    // =====================================================================

    public static ProductFingerprint fromRow(ParsedRow row) {
        String raw = row.rawTitle != null ? row.rawTitle : (row.name == null ? "" : row.name);
        ProductFingerprint fp = new ProductFingerprint();
        fp.brandTokens = brandTokens(row.brand);
        fp.ml = row.ml;
        fp.gender = detectGender(raw);
        fp.concentration = detectConcentration(raw);
        fp.presentation = detectPresentation(raw, row.forma);
        fp.coreTokens = coreTokens(row.name != null && !row.name.isBlank() ? row.name : raw,
                fp.brandTokens, row.ml);
        return fp;
    }

    public static ProductFingerprint fromProduct(Product p) {
        // El nombre del producto ya viene limpio (cleanName); genero/concentracion se
        // buscan tambien en type ("Women EDP" del seed) y category (women/men/unisex).
        String searchText = (nz(p.getName()) + " " + nz(p.getType()) + " " + nz(p.getCategory()));
        ProductFingerprint fp = new ProductFingerprint();
        fp.brandTokens = brandTokens(p.getBrand());
        fp.ml = p.getMl();
        fp.gender = detectGender(searchText);
        fp.concentration = detectConcentration(searchText);
        fp.presentation = detectPresentation(searchText, p.getForma());
        fp.coreTokens = coreTokens(nz(p.getName()), fp.brandTokens, p.getMl());
        return fp;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // =====================================================================

    static Set<String> brandTokens(String brand) {
        Set<String> out = new LinkedHashSet<>();
        if (brand == null) return out;
        String base = PerfumeNormalizer.stripAccents(brand).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ");
        for (String t : base.split("\\s+")) {
            if (t.isEmpty()) continue;
            t = BRAND_TOKEN_FIXES.getOrDefault(t, t);
            out.add(t);
        }
        return out;
    }

    static String detectGender(String text) {
        if (text == null) return null;
        String t = PerfumeNormalizer.stripAccents(text).toLowerCase(Locale.ROOT);
        // Orden importa: "women" contiene "men".
        if (WOMEN.matcher(t).find()) return "women";
        if (MEN.matcher(t).find()) return "men";
        if (UNISEX.matcher(t).find()) return "unisex";
        return null;
    }

    static String detectConcentration(String text) {
        if (text == null) return null;
        String t = PerfumeNormalizer.stripAccents(text).toLowerCase(Locale.ROOT);
        // Frases completas primero ("eau de parfum" contiene "parfum").
        if (t.contains("eau de parfum")) return "edp";
        if (t.contains("eau de toilette")) return "edt";
        if (t.contains("eau de cologne")) return "edc";
        if (t.matches(".*\\bextrait\\b.*")) return "extrait";
        if (t.matches(".*\\bedp\\b.*")) return "edp";
        if (t.matches(".*\\bedt\\b.*")) return "edt";
        if (t.matches(".*\\bedc\\b.*")) return "edc";
        if (t.matches(".*\\bcologne\\b.*")) return "edc";
        if (t.matches(".*\\bparfum\\b.*")) return "parfum";
        return null;
    }

    static String detectPresentation(String text, String forma) {
        String t = text == null ? "" : PerfumeNormalizer.stripAccents(text).toLowerCase(Locale.ROOT);
        if (TESTER.matcher(t).find()) return "tester";
        if ("set".equals(forma)) return "set";
        if ("oil".equals(forma)) return "oil";
        if ("deo".equals(forma)) return "deo";
        if (MINI.matcher(t).find()) return "mini";
        return "regular";
    }

    /**
     * Tokens del nombre sin marca, sin descriptores y sin numeros de tamano.
     * Conserva tokens alfanumericos ("9pm") y con puntos ("l.12.12").
     */
    static Set<String> coreTokens(String name, Set<String> brandTokens, Integer ml) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) return out;
        String base = PerfumeNormalizer.stripAccents(name).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9. ]", " ");
        for (String t : base.split("\\s+")) {
            t = t.replaceAll("^\\.+|\\.+$", ""); // puntos en los bordes fuera; "l.12.12" queda
            if (t.isEmpty()) continue;
            if (DESCRIPTOR_TOKENS.contains(t)) continue;
            if (brandTokens.contains(t)) continue;
            if (t.matches("\\d+(\\.\\d+)?")) {
                if (isSizeNumber(t, ml)) continue;
                // Numero que forma parte del nombre (212, 360...): se conserva.
            }
            out.add(t);
        }
        return out;
    }

    /** Un numero es "de tamano" si coincide con los ml o con su equivalente en onzas. */
    private static boolean isSizeNumber(String token, Integer ml) {
        double v;
        try { v = Double.parseDouble(token); } catch (NumberFormatException e) { return false; }
        if (ml != null) {
            if (Math.abs(v - ml) < 0.01) return true;                       // "100" con ml=100
            double asOzMl = v * 29.5735;
            if (Math.abs(asOzMl - ml) <= Math.max(3, ml * 0.05)) return true; // "3.4" con ml=100
        } else {
            // Sin ml conocido: los patrones tipicos de onzas (0.5 - 8.5 con decimales) se descartan.
            if (v > 0 && v < 9 && token.contains(".")) return true;
        }
        return false;
    }
}
