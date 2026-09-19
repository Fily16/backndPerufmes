package org.example.backendbvaberiaperfumes.service.nso;

import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizador de nombres para el reconocimiento NSO (puro, determinista, sin Spring ni BD).
 *
 * Convierte un texto de proveedor ("Supremacy Silver 3.4 Oz Edp Men") o de aduanas
 * ("AGUA DE PERFUME SUPREMACY SILVER") en un NormalizedName: palabras del nombre (core) + atributos.
 *
 * Reglas (ver especificacion NSO, seccion 1):
 *  1. Plegar: sin acentos, minusculas, & -> and, "+" con espacios.
 *  2. Quitar la marca como FRASE (asi "DONNA KARAN" no se lee como genero "donna").
 *  3. Extraer atributos ANTES de limpiar: concentracion (dos distintas -> AMBIGUA), genero, tester, forma, ml.
 *  4. Quitar ruido (tamanos, codigos RE26/MD26, anios, numeros sueltos), palabras genericas, stopwords,
 *     palabras de genero/tester y palabras sueltas de marca; plegar sinonimos (bleu -> blue, inten -> intense).
 * El ml solo sirve para desempatar: el NSO no depende del tamano.
 */
public final class NsoNormalizer {

    private NsoNormalizer() {}

    // --- concentracion ---
    public static final String EDP = "edp";
    public static final String EDT = "edt";
    public static final String EDC = "edc";
    public static final String PARFUM = "parfum";
    /** Dos concentraciones distintas en el mismo texto (o entre ofertas): nunca automatico. */
    public static final String AMBIGUA = "AMBIGUA";

    // --- genero ---
    public static final String WOMEN = "women";
    public static final String MEN = "men";
    public static final String UNISEX = "unisex";

    // --- forma ---
    public static final String FORMA_PERFUME = "perfume";
    public static final String FORMA_SET = "set";
    public static final String FORMA_BODY = "body";
    public static final String FORMA_OIL = "oil";

    /** Aduanas corta el nombre declarado a 35 letras. */
    public static final int TRUNCATED_LENGTH = 35;
    public static final int TRUNCATED_MIN_LENGTH = 33;

    // ===================== resultado =====================

    /** Nombre normalizado: palabras del nombre + atributos. Inmutable. */
    public static final class NormalizedName {
        /** Texto original. */
        public final String raw;
        /** Largo del texto original sin espacios en los bordes (para detectar nombres cortados). */
        public final int rawLength;
        /** Palabras del nombre en el orden del texto (sin marca, genericas, ruido, genero, tester). */
        public final List<String> tokens;
        /** Palabra de origen de cada token (antes de sinonimos): "inten" para "intense". Paralela a tokens. */
        public final List<String> sourceTokens;
        /** Todas las palabras plegadas del texto original (para la regla de nombre cortado). */
        public final List<String> words;
        /** Ultima palabra plegada del texto original ("sk" en "...MANDARIN SK"). */
        public final String lastWord;
        /** edp | edt | edc | parfum | AMBIGUA | null */
        public final String concentration;
        /** women | men | unisex | AMBIGUA | null */
        public final String gender;
        /** El genero sale de una palabra que es parte del nombre (homme, femme, him, her, uomo, donna...). */
        public final boolean genderInName;
        /** Palabra de genero tal cual aparecio en el nombre ("homme"), para los motivos. */
        public final String genderWord;
        public final boolean tester;
        /** perfume | set | body | oil */
        public final String forma;
        public final Integer ml;

        NormalizedName(String raw, List<String> tokens, List<String> sourceTokens, List<String> words,
                       String concentration, String gender, boolean genderInName, String genderWord,
                       boolean tester, String forma, Integer ml) {
            this.raw = raw == null ? "" : raw;
            this.rawLength = this.raw.trim().length();
            this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
            this.sourceTokens = Collections.unmodifiableList(new ArrayList<>(sourceTokens));
            this.words = Collections.unmodifiableList(new ArrayList<>(words));
            this.lastWord = words.isEmpty() ? "" : words.get(words.size() - 1);
            this.concentration = concentration;
            this.gender = gender;
            this.genderInName = genderInName;
            this.genderWord = genderWord;
            this.tester = tester;
            this.forma = forma;
            this.ml = ml;
        }

        /** Tokens ordenados alfabeticamente. */
        public List<String> core() {
            List<String> c = new ArrayList<>(tokens);
            Collections.sort(c);
            return c;
        }

        /** Tokens unidos sin espacios, en el orden del texto. */
        public String compact() {
            return String.join("", tokens);
        }

        /** Tokens ordenados y unidos sin espacios: "9 am dive" y "9am dive" dan lo mismo. */
        public String sortedCompact() {
            return String.join("", core());
        }

        /** Sin palabras de nombre: "AGUA DE PERFUME", "COLONIA". Nunca coincide por nombre. */
        public boolean isGeneric() {
            return tokens.isEmpty();
        }

        /** Largo exacto de 35 letras: casi seguro cortado por aduanas. */
        public boolean isTruncatedSuspect() {
            return rawLength == TRUNCATED_LENGTH;
        }

        /** Largo 33..35: la ultima palabra puede estar cortada (se confirma al comparar). */
        public boolean isTruncatable() {
            return rawLength >= TRUNCATED_MIN_LENGTH && rawLength <= TRUNCATED_LENGTH;
        }

        /** El ultimo token viene de la ultima palabra del texto (no hay genero/tamano/ruido despues). */
        public boolean lastTokenIsLastWord() {
            if (sourceTokens.isEmpty() || lastWord.isEmpty()) return false;
            // Igualdad estricta: "ONE100" / "INT200" terminan en un tamano completo, el nombre no esta cortado.
            return lastWord.equals(sourceTokens.get(sourceTokens.size() - 1));
        }

        @Override
        public String toString() {
            return tokens + " conc=" + concentration + " gender=" + gender + (genderInName ? "(nombre)" : "")
                    + " forma=" + forma + (tester ? " tester" : "") + " ml=" + ml;
        }
    }

    // ===================== diccionarios =====================

    private static final Pattern EDP_P = Pattern.compile(
            "\\b(agua de perfume|agua de parfum|eau de parfum|eau de perfume|eau de parfume|edp)\\b");
    private static final Pattern EDT_P = Pattern.compile("\\b(agua de tocador|eau de toilette|edt)\\b");
    private static final Pattern EDC_P = Pattern.compile("\\b(agua de colonia|eau de cologne|colonia|cologne|edc)\\b");
    private static final Pattern PARFUM_P = Pattern.compile(
            "\\b(extracto de perfume|extrait de parfum|extrait|parfum|parfume)\\b");

    private static final Pattern TESTER_P = Pattern.compile("\\b(tt|tst|tstr|tester|testr|probador)\\b");

    private static final Pattern SET_P = Pattern.compile(
            "\\s\\+\\s|\\b(set|sets|kit|coffret|gift|estuche)\\b|\\b\\d+\\s*(pc|pcs|psc|pz|pzs|pieces|piezas)\\b"
                    + "|\\b(pcs|psc)\\b|\\([^)]*\\d\\s*(ml|oz)[^)]*\\)|\\d\\s*ml\\s*x\\s*\\d");
    private static final Pattern BODY_P = Pattern.compile(
            "body spray|body mist|body lotion|\\bdeo\\b|desodorante|deodorant|\\bbl(\\s?\\d+|\\b)|\\blocion\\b"
                    + "|\\blotion\\b|\\bgel\\b|body cream|crema corporal|hand cream|all over|all-over|hair mist|hair perfume"
                    + "|\\bbruma\\b|cabello|after shave|aftershave|\\bshower\\b|\\basb\\s?\\d+");
    private static final Pattern OIL_P = Pattern.compile("\\boil\\b|\\baceite\\b|\\battar\\b|\\bcpo\\b");

    private static final Pattern ML_P = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ml\\b");
    private static final Pattern OZ_P = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:fl\\.?\\s*)?oz\\b");
    private static final String SIZE_NUMBERS = "30|50|60|75|80|90|100|105|110|120|125|150|200";
    private static final Pattern GLUED_ML_P = Pattern.compile("\\b([a-z]{3,})(" + SIZE_NUMBERS + ")\\b");

    /** Palabras de genero. women se evalua antes que men (WOMEN contiene MEN). */
    private static final Set<String> WOMEN_WORDS = Set.of(
            "women", "woman", "womens", "wom", "femme", "fem", "her", "hers", "donna", "lei", "mujer", "dama",
            "damas", "ladies", "female", "w");
    private static final Set<String> MEN_WORDS = Set.of(
            "men", "man", "mens", "homme", "him", "his", "uomo", "lui", "hombre", "caballero", "caballeros", "m");
    private static final Set<String> UNISEX_WORDS = Set.of("unisex", "u");
    /** Palabras de genero que ademas son parte del nombre ("Pour Homme", "Burberry Her"). */
    private static final Set<String> NAME_GENDER_WORDS = Set.of(
            "femme", "her", "hers", "donna", "lei", "homme", "him", "his", "uomo", "lui");
    /** Palabras de genero largas contra las que se tolera un typo (HOMEE, HOMM, WOMEM). */
    private static final List<String> FUZZY_GENDER = List.of("homme", "femme", "women", "woman");

    private static final Set<String> TESTER_WORDS = Set.of("tt", "tst", "tstr", "tester", "testr", "probador");

    /** Palabras genericas: no distinguen un perfume de otro. OJO: elixir, intense, absolu, extreme NO van aqui. */
    private static final Set<String> GENERIC_WORDS = Set.of(
            "agua", "perfume", "perfumes", "perfumado", "perfumada", "parfum", "parfume", "toilette", "tocador",
            "colonia", "cologne", "extracto", "extrait", "edp", "edt", "edc", "spray", "vapo", "vaporizador",
            "vaporisateur", "natural", "refillable", "recargable", "rechargeable", "refill", "splash",
            "fragrance", "fragance", "frag", "oz", "ml", "fl", "mi", "cuerpo", "corporal", "lp");

    /** Stopwords (es/en/fr/it) y palabras de edicion. */
    private static final Set<String> STOPWORDS = Set.of(
            "de", "d", "del", "of", "the", "la", "le", "l", "for", "pour", "by", "and", "y", "et", "di", "el",
            "a", "new", "edition", "ed", "para", "con", "en", "s");

    /** Ruido suelto de aduanas y proveedores (vaporizador, registro sanitario, etc.). */
    private static final Set<String> LOOSE_NOISE = Set.of(
            "re", "ns", "it", "ip", "sp", "vap", "vp", "anual", "nat", "reg", "rpk");

    /** Palabras que describen la presentacion detectada (se quitan del nombre solo con esa forma). */
    private static final Map<String, Set<String>> FORMA_WORDS = Map.of(
            FORMA_BODY, Set.of("body", "mist", "deo", "deodorant", "desodorante", "lotion", "locion", "gel",
                    "crema", "cream", "shower", "showergel", "bruma", "all", "over", "hair", "cabello", "bl"),
            FORMA_SET, Set.of("set", "sets", "gift", "kit", "coffret", "estuche", "pc", "pcs", "psc", "pz", "pzs",
                    "pieces", "piezas"),
            FORMA_OIL, Set.of("oil", "aceite", "attar", "cpo"));

    /** Sinonimos (diccionario chico y testeado). */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("bleu", List.of("blue")),
            Map.entry("azul", List.of("blue")),
            Map.entry("noir", List.of("black")),
            Map.entry("negro", List.of("black")),
            Map.entry("rouge", List.of("red")),
            Map.entry("rojo", List.of("red")),
            Map.entry("blanc", List.of("white")),
            Map.entry("blanche", List.of("white")),
            Map.entry("blanco", List.of("white")),
            Map.entry("inten", List.of("intense")),
            Map.entry("intens", List.of("intense")),
            Map.entry("intenso", List.of("intense")),
            Map.entry("int", List.of("intense")),
            Map.entry("lm", List.of("male")),
            Map.entry("inv", List.of("invictus")),
            Map.entry("1m", List.of("1", "million")),
            Map.entry("one1million", List.of("1", "million"))
    );

    // ===================== API =====================

    /** Normaliza sin palabras de marca. */
    public static NormalizedName normalize(String text) {
        return normalize(text, List.of());
    }

    /**
     * Normaliza quitando las palabras de marca indicadas. brandTerms puede traer frases ("donna karan",
     * "carolina herrera") y abreviaturas ("ch", "ab"): las frases se quitan enteras ANTES de leer atributos y
     * cada palabra suelta se quita despues.
     */
    public static NormalizedName normalize(String text, Collection<String> brandTerms) {
        String raw = text == null ? "" : text;
        String t = fold(raw);
        List<String> words = plainWords(t);

        // 2. Marca como frase (las mas largas primero).
        Set<String> brandWords = new LinkedHashSet<>();
        List<String> phrases = new ArrayList<>();
        if (brandTerms != null) {
            for (String term : brandTerms) {
                String f = NsoKeys.fold(term);
                if (f.isEmpty()) continue;
                phrases.add(f);
                Collections.addAll(brandWords, f.split(" "));
            }
        }
        phrases.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        for (String p : phrases) {
            if (!p.contains(" ")) continue;
            String rx = "(?<![a-z0-9])" + String.join("[\\s\\-_./]+", p.split(" ")) + "(?![a-z0-9])";
            t = t.replaceAll(rx, " ");
        }
        t = t.replaceAll("\\bw\\s*/\\s*", " "); // "W/ CAP" no es genero
        // "Le Parfum" es nombre de version (Le Male Le Parfum, Idole Le Parfum, Scandal Le Parfum EDP), no la
        // concentracion: queda como palabra del nombre para que no se confunda con el perfume base.
        t = t.replaceAll("\\ble\\s*[-.]?\\s*(parfum|perfume)\\b|\\blp\\b", " leparfum ");

        // 3. Atributos.
        String plain = " " + t.replaceAll("[^a-z0-9+ ]", " ").replaceAll("\\s+", " ").trim() + " ";
        String concentration = detectConcentration(plain);
        GenderInfo gi = detectGender(plain);
        boolean tester = TESTER_P.matcher(plain).find();
        String forma = detectForma(t);
        Integer ml = detectMl(t);

        // 4. Ruido. Las frases de concentracion se quitan enteras: "eau" suelto SI es nombre (Eau Sauvage,
        // Eau Fraiche, L'Eau d'Issey), pero "eau de parfum" no.
        String n = t;
        n = n.replaceAll("\\b(agua|eau)\\s+de\\s+(perfume|parfum|parfume|toilette|tocador|cologne|colonia)\\b", " ");
        n = n.replaceAll("\\b(extracto|extrait)\\s+de\\s+(perfume|parfum)\\b", " ");
        n = n.replaceAll("\\bone\\s+million\\b", " one1million "); // "One Million" == "1 MILLION"
        n = n.replaceAll("\\bv?\\d+(\\.\\d+)?\\s*(ml|mi)\\b", " ");
        n = n.replaceAll("\\d+(\\.\\d+)?\\s*(fl\\.?\\s*)?oz\\b", " ");
        n = n.replaceAll("\\b(" + SIZE_NUMBERS + ")\\s*m\\b", " "); // "100M" (ml cortado), no "212 M"
        n = n.replaceAll("\\b\\d+(\\.\\d+)?\\s*(g|gr|grs)\\b", " ");
        n = GLUED_ML_P.matcher(n).replaceAll("$1");
        n = n.replaceAll("\\b[a-z]{2}\\d{2}\\b", " ");
        n = n.replaceAll("\\b(re|le|lim)\\s?\\d{2}\\b", " ");
        n = n.replaceAll("\\(\\s*\\d+\\s*\\)", " ");
        n = n.replaceAll("/\\s*(mad|np|dev|rfl)\\b", " ");
        n = n.replaceAll("\\br\\d\\b", " "); // "PI EDT 100ML R2"
        n = n.replaceAll("\\b\\d+\\s*(pc|pcs|psc|pz|pzs|pieces|piezas)\\b", " "); // "2 Pcs", "3 PSC"
        n = n.replaceAll("\\b(vp|vap|ns|iv)\\s+\\d{2}\\b", " "); // "AB QUEEN EDT 80ML VP 20"
        n = n.replaceAll("\\bc\\s*/\\s*\\d+\\s*ud\\b", " ");
        n = n.replaceAll("\\b(19|20)\\d{2}\\b", " ");
        n = n.replaceAll("\\b\\d+\\.\\d+\\b", " ");
        n = n.replaceAll("\\b\\d{5,}\\b", " ");
        n = n.replaceAll("\\b(" + SIZE_NUMBERS + ")\\b", " ");
        n = n.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();

        List<String> tokens = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> removedNameGender = new ArrayList<>();
        if (!n.isEmpty()) {
            for (String w : n.split(" ")) {
                if (w.isEmpty()) continue;
                if (LOOSE_NOISE.contains(w) || GENERIC_WORDS.contains(w) || STOPWORDS.contains(w)
                        || TESTER_WORDS.contains(w)) continue;
                if (isGenderWord(w)) {
                    if (NAME_GENDER_WORDS.contains(w)) removedNameGender.add(w);
                    continue;
                }
                if (brandWords.contains(w)) continue;
                // Palabras de presentacion: ya quedan en "forma"; solo se quitan si esa forma se detecto
                // ("Burberry Body" es un perfume y "body" es su nombre).
                Set<String> formaWords = FORMA_WORDS.get(forma);
                if (formaWords != null && formaWords.contains(w)) continue;
                // Un set suele traer body lotion / deo / shower gel: esas palabras tampoco son el nombre.
                if (FORMA_SET.equals(forma) && FORMA_WORDS.get(FORMA_BODY).contains(w)) continue;
                List<String> syn = SYNONYMS.get(w);
                for (String tk : syn != null ? syn : List.of(w)) {
                    // Sin repetir ("ACQUA DI GIO ELIXIR 50ML ELIXIR SPRAY" -> una sola vez elixir).
                    if (tokens.contains(tk)) continue;
                    tokens.add(tk);
                    sources.add(w);
                }
            }
        }
        // "Burberry Her", "Versace Pour Homme": si el nombre SOLO era la palabra de genero, esa palabra es el nombre.
        if (tokens.isEmpty() && !removedNameGender.isEmpty()) {
            for (String w : removedNameGender) {
                if (!tokens.contains(w)) {
                    tokens.add(w);
                    sources.add(w);
                }
            }
        }
        return new NormalizedName(raw, tokens, sources, words, concentration, gi.gender, gi.inName, gi.word,
                tester, forma, ml);
    }

    private static final Pattern DECLARED_GFH = Pattern.compile("(?i)\\bgfh\\b");
    private static final Pattern DECLARED_PH = Pattern.compile("(?i)\\bph\\b");
    private static final Pattern DECLARED_FH = Pattern.compile("(?i)\\bfh\\b");
    /**
     * "L" suelta de ladies (version mujer), como la "M" de men: "LATTAFA QIMMAH 3.4 EDP L (126994)". Solo como
     * ULTIMA palabra (con o sin el numero entre parentesis) o justo despues de EDP/EDT/EDC; nunca el articulo
     * frances "L'" (L'AVENTURE, L EAU D ISSEY, L HOMME, L ABSOLUE), que va seguido de otra palabra del nombre.
     */
    private static final Pattern DECLARED_LADIES = Pattern.compile(
            "(?i)(?<=\\bed[ptc]\\s)l(?=\\s|$)|\\bl(?=\\s*(\\(\\s*\\d+\\s*\\))?\\s*$)");

    /** Palabras de genero cuyo inicio puede quedar suelto al final de un nombre cortado ("INT MA", "POUR HOM"). */
    private static final List<String> CUT_GENDER_WORDS = List.of("homme", "hombre", "femme", "women", "woman", "mujer", "man");

    /**
     * Normaliza un nombre DECLARADO en aduanas. Igual que normalize, mas una regla propia de los nombres
     * cortados a 33..35 letras: si la ultima palabra es el inicio de una palabra de genero ("CLUB DE NUIT INT MA"),
     * se lee como ese genero y no como palabra del nombre.
     */
    public static NormalizedName normalizeDeclared(String text, Collection<String> brandTerms) {
        // Abreviaturas de genero de los importadores: "MILLION GFH" = Gold For Her, "GG PH" = Pour Homme,
        // "NARCISO RODRIGUEZ FH" = For Her. Solo en nombres declarados (en un titulo de proveedor serian ambiguas).
        String expanded = text == null ? null : DECLARED_GFH.matcher(text).replaceAll(" GOLD FOR HER ");
        if (expanded != null) expanded = DECLARED_PH.matcher(expanded).replaceAll(" POUR HOMME ");
        if (expanded != null) expanded = DECLARED_FH.matcher(expanded).replaceAll(" FOR HER ");
        // "... EDP L" = ladies: genero mujer (no es palabra del nombre, igual que la "M" suelta de men).
        if (expanded != null) expanded = DECLARED_LADIES.matcher(expanded).replaceAll(" WOMEN ");
        NormalizedName n = normalize(expanded, brandTerms);
        n = new NormalizedName(text == null ? "" : text, n.tokens, n.sourceTokens, n.words, n.concentration, n.gender,
                n.genderInName, n.genderWord, n.tester, n.forma, n.ml);
        if (!n.isTruncatable() || n.gender != null || n.tokens.size() < 2 || !n.lastTokenIsLastWord()) return n;
        String last = n.lastWord;
        if (last.length() < 2 || last.length() > 4 || !last.chars().allMatch(Character::isLetter)) return n;
        String gender = null;
        for (String g : CUT_GENDER_WORDS) {
            if (g.length() > last.length() && g.startsWith(last)) {
                gender = genderOf(g);
                break;
            }
        }
        if (gender == null) return n;
        List<String> tokens = new ArrayList<>(n.tokens.subList(0, n.tokens.size() - 1));
        List<String> sources = new ArrayList<>(n.sourceTokens.subList(0, n.sourceTokens.size() - 1));
        return new NormalizedName(n.raw, tokens, sources, n.words, n.concentration, gender, false, last,
                n.tester, n.forma, n.ml);
    }

    /** Plegado base: sin acentos, minusculas, & -> and, guiones normalizados, "+" separado. Conserva ( ) / . + - */
    public static String fold(String s) {
        if (s == null) return "";
        String t = PerfumeNormalizer.stripAccents(s).toLowerCase(Locale.ROOT);
        t = t.replace("&", " and ");
        t = t.replaceAll("[\\u2010-\\u2015\\u2212]", "-");
        t = t.replaceAll("(\\d),(\\d)", "$1.$2");
        t = t.replace("+", " + ");
        t = t.replaceAll("[^a-z0-9+()/.\\- ]", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Todas las palabras alfanumericas del texto plegado. */
    private static List<String> plainWords(String folded) {
        List<String> out = new ArrayList<>();
        for (String w : folded.replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    // ===================== atributos =====================

    static String detectConcentration(String plain) {
        Set<String> found = new LinkedHashSet<>();
        String rest = plain;
        Matcher m = EDP_P.matcher(rest);
        if (m.find()) { found.add(EDP); rest = EDP_P.matcher(rest).replaceAll(" "); }
        m = EDT_P.matcher(rest);
        if (m.find()) { found.add(EDT); rest = EDT_P.matcher(rest).replaceAll(" "); }
        m = EDC_P.matcher(rest);
        if (m.find()) { found.add(EDC); rest = EDC_P.matcher(rest).replaceAll(" "); }
        m = PARFUM_P.matcher(rest);
        if (m.find()) found.add(PARFUM);
        if (found.isEmpty()) return null;
        if (found.size() > 1) return AMBIGUA;
        return found.iterator().next();
    }

    private static final class GenderInfo {
        String gender;
        boolean inName;
        String word;
    }

    private static GenderInfo detectGender(String plain) {
        GenderInfo gi = new GenderInfo();
        boolean women = false, men = false, unisex = false;
        String womenWord = null, menWord = null;
        boolean womenInName = false, menInName = false;
        for (String w : plain.trim().split(" ")) {
            if (w.isEmpty()) continue;
            String g = genderOf(w);
            if (g == null) continue;
            switch (g) {
                case WOMEN -> {
                    women = true;
                    if (NAME_GENDER_WORDS.contains(w)) { womenInName = true; womenWord = w; }
                    else if (womenWord == null) womenWord = w;
                }
                case MEN -> {
                    men = true;
                    if (NAME_GENDER_WORDS.contains(w)) { menInName = true; menWord = w; }
                    else if (menWord == null) menWord = w;
                }
                default -> unisex = true;
            }
        }
        if (women && men) {
            gi.gender = AMBIGUA;
        } else if (women) {
            gi.gender = WOMEN;
            gi.inName = womenInName;
            gi.word = womenWord;
        } else if (men) {
            gi.gender = MEN;
            gi.inName = menInName;
            gi.word = menWord;
        } else if (unisex) {
            gi.gender = UNISEX;
        }
        return gi;
    }

    /** Genero de UNA palabra (con typo de 1 letra en homme/femme/women/woman), o null. */
    static String genderOf(String w) {
        if (WOMEN_WORDS.contains(w)) return WOMEN;
        if (MEN_WORDS.contains(w)) return MEN;
        if (UNISEX_WORDS.contains(w)) return UNISEX;
        if (w.length() >= 4 && !w.equals("home")) {
            for (String g : FUZZY_GENDER) {
                if (w.charAt(0) == g.charAt(0) && NsoText.editDistanceIsOne(w, g)) {
                    return g.startsWith("wom") || g.equals("femme") ? WOMEN : MEN;
                }
            }
        }
        return null;
    }

    private static boolean isGenderWord(String w) {
        return genderOf(w) != null;
    }

    static String detectForma(String folded) {
        String t = " " + folded + " ";
        if (SET_P.matcher(t).find()) return FORMA_SET;
        if (BODY_P.matcher(t).find()) return FORMA_BODY;
        if (OIL_P.matcher(t).find()) return FORMA_OIL;
        return FORMA_PERFUME;
    }

    static Integer detectMl(String folded) {
        Matcher m = ML_P.matcher(folded);
        if (m.find()) {
            try {
                return (int) Math.round(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException ignored) {
                // sigue con onzas
            }
        }
        m = OZ_P.matcher(folded);
        if (m.find()) {
            try {
                double oz = Double.parseDouble(m.group(1));
                return (int) Math.round(oz * 29.5735 / 5.0) * 5;
            } catch (NumberFormatException ignored) {
                // sigue con pegado
            }
        }
        m = GLUED_ML_P.matcher(folded);
        if (m.find()) return Integer.parseInt(m.group(2));
        return null;
    }

    /** Etiqueta en espanol de una concentracion, para los motivos. */
    public static String concentrationLabel(String c) {
        if (c == null) return "sin concentración";
        if (AMBIGUA.equals(c)) return "ambigua";
        return c.toUpperCase(Locale.ROOT);
    }

    /** Etiqueta en espanol de un genero, para los motivos. */
    public static String genderLabel(String g) {
        if (g == null) return "sin género";
        return switch (g) {
            case WOMEN -> "mujer";
            case MEN -> "hombre";
            case UNISEX -> "unisex";
            default -> "ambiguo";
        };
    }

    /** Etiqueta en espanol de una forma, para los motivos. */
    public static String formaLabel(String f) {
        if (f == null) return "perfume";
        return switch (f) {
            case FORMA_SET -> "set";
            case FORMA_BODY -> "body spray / crema";
            case FORMA_OIL -> "aceite";
            default -> "perfume";
        };
    }
}
