package org.example.backendbvaberiaperfumes.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizacion de nombres de perfumes y codigos de barras.
 * Determinista, sin dependencias externas (no NLP). Se usa para:
 *  - llave de matching (slug) y deteccion de colisiones de UPC,
 *  - parseo de tamano (Oz/ML) y forma (set/oil/deo).
 */
public final class PerfumeNormalizer {

    private PerfumeNormalizer() {}

    private static final Set<String> STOPWORDS = Set.of(
            "edp", "edt", "parfum", "perfume", "eau", "de", "toilette", "cologne",
            "spray", "for", "women", "woman", "men", "man", "unisex", "oz", "ml",
            "hb", "pour", "homme", "femme", "the", "by", "new"
    );

    private static final Pattern OZ = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*oz");
    private static final Pattern ML = Pattern.compile("([0-9]+)\\s*ml");
    private static final Pattern SET = Pattern.compile("\\b(set|gift\\s*set|coffret|kit)\\b|\\b\\d\\s*pc|\\bpcs\\b");
    private static final Pattern OIL = Pattern.compile("\\b(oil|attar|cpo|extrait\\s*oil)\\b");
    private static final Pattern DEO = Pattern.compile("\\b(deo|deodorant|body\\s*spray|desodorante)\\b");

    /** Devuelve solo digitos del codigo, normalizado a GTIN-14. Null si no parece un UPC/EAN valido. */
    public static String gtin14(Object raw) {
        if (raw == null) return null;
        String s;
        if (raw instanceof Number) {
            // evita notacion cientifica para numeros grandes
            s = new java.math.BigDecimal(raw.toString()).toBigInteger().toString();
        } else {
            s = raw.toString();
        }
        String digits = s.replaceAll("\\D", "");
        if (digits.length() < 11 || digits.length() > 14) return null;
        return String.format("%14s", digits).replace(' ', '0');
    }

    public static String stripAccents(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    /** Conjunto de tokens significativos de (marca + nombre), sin acentos, sin stopwords ni numeros sueltos. */
    public static Set<String> tokens(String brand, String name) {
        String base = stripAccents(((brand == null ? "" : brand) + " " + (name == null ? "" : name))).toLowerCase();
        base = base.replaceAll("[^a-z0-9 ]", " ");
        Set<String> out = new LinkedHashSet<>();
        for (String t : base.split("\\s+")) {
            if (t.isEmpty()) continue;
            if (STOPWORDS.contains(t)) continue;
            if (t.matches("\\d+(\\.\\d+)?")) continue; // numeros sueltos (tamanos) fuera
            out.add(t);
        }
        return out;
    }

    /** Llave estable y legible para identificar un perfume: "marca-nombre-ml". */
    public static String slug(String brand, String name, Integer ml) {
        String joined = String.join("-", tokens(brand, name));
        if (joined.isEmpty()) joined = "item";
        return joined + (ml != null && ml > 0 ? "-" + ml : "");
    }

    /** Similitud Jaccard de tokens [0..1]. */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * Dos filas con el MISMO UPC, son el mismo producto? (guardia anti-colision).
     * Al compartir UPC asumimos colision salvo que sean CASI identicos: un duplicado real
     * de la misma fila tiene Jaccard ~1.0; nombres distintos (Ajman vs Umm Al Quwain) deben
     * tratarse como productos separados. Umbral alto (0.8) para no fusionar cosas distintas.
     */
    public static boolean sameProduct(String brandA, String nameA, Integer mlA,
                                      String brandB, String nameB, Integer mlB) {
        if (mlA != null && mlB != null && !mlA.equals(mlB)) return false;
        double sim = jaccard(tokens(brandA, nameA), tokens(brandB, nameB));
        return sim >= 0.8;
    }

    /** Extrae ml desde un texto en onzas (Zimaxx). 3.4oz->100, 1.7oz->50, etc. */
    public static Integer mlFromOz(String text) {
        if (text == null) return null;
        Matcher m = OZ.matcher(stripAccents(text).toLowerCase());
        if (!m.find()) return null;
        double oz = Double.parseDouble(m.group(1));
        return (int) Math.round(oz * 29.5735 / 5.0) * 5; // ml redondeado a multiplos de 5
    }

    /** Extrae ml desde un texto en ml (Magnet). "100ML" -> 100. */
    public static Integer mlFromMl(String text) {
        if (text == null) return null;
        Matcher m = ML.matcher(stripAccents(text).toLowerCase());
        if (!m.find()) return null;
        return Integer.parseInt(m.group(1));
    }

    /** single | set | oil | deo */
    public static String detectForma(String text) {
        if (text == null) return "single";
        String t = stripAccents(text).toLowerCase();
        if (SET.matcher(t).find()) return "set";
        if (DEO.matcher(t).find()) return "deo";
        if (OIL.matcher(t).find()) return "oil";
        return "single";
    }

    /** Quita el prefijo de marca repetido al inicio del titulo (Magnet: "LATTAFA MAYAR" -> "MAYAR"). */
    public static String stripBrandPrefix(String brand, String title) {
        if (brand == null || title == null) return title;
        String b = stripAccents(brand).toLowerCase().trim();
        String firstWord = b.split("\\s+")[0];
        String t = title.trim();
        String tl = stripAccents(t).toLowerCase();
        if (tl.startsWith(b)) {
            return t.substring(Math.min(brand.trim().length(), t.length())).replaceFirst("^[\\s\\-:]+", "").trim();
        }
        if (!firstWord.isEmpty() && tl.startsWith(firstWord)) {
            return t.substring(Math.min(firstWord.length(), t.length())).replaceFirst("^[\\s\\-:]+", "").trim();
        }
        return t;
    }

    /** Limpia un titulo para usarlo como nombre legible: quita tamano, genero y concentracion al final. */
    public static String cleanName(String title) {
        if (title == null) return "";
        String t = title;
        t = t.replaceAll("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*oz", " ");
        t = t.replaceAll("(?i)([0-9]+)\\s*ml", " ");
        t = t.replaceAll("(?i)\\b(edp|edt|parfum|spray|women|woman|men|man|unisex|hb|pour homme|pour femme)\\b", " ");
        t = t.replaceAll("\\s{2,}", " ").trim();
        // recorta separadores sobrantes
        t = t.replaceAll("[\\-:]+$", "").trim();
        return t.isEmpty() ? title.trim() : t;
    }

    public static String[] words(String s) {
        return Arrays.stream(stripAccents(s).toLowerCase().split("\\s+"))
                .filter(w -> !w.isEmpty()).toArray(String[]::new);
    }
}
