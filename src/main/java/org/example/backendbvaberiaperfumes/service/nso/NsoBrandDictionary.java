package org.example.backendbvaberiaperfumes.service.nso;

import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.example.backendbvaberiaperfumes.model.NsoRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Diccionario de marcas del reconocimiento NSO (puro, determinista, sin Spring ni BD).
 *
 * Resuelve la marca que escribe un proveedor ("Dolce & Gabbana", "CAROLINA" + "HERRERA GOOD GIRL",
 * "Mont Blanc", "United Colors of Benetton") a la marca del catalogo NSO. Una marca "canonica" agrupa las
 * variantes que el catalogo trae por separado (DIOR y CHRISTIAN DIOR, RABANNE y PACO RABANNE...), para que
 * un perfume se compare contra TODOS los registros de su marca.
 *
 * Orden de resolve(marca, titulos...):
 *  1. Alias BRAND de la admin (tienen prioridad).
 *  2. Marca exacta / alias fijo / sin espacios (MONT BLANC == MONTBLANC).
 *  3. Contencion de palabra completa ("united colors of benetton" contiene "benetton"), la mas larga.
 *  4. Prefijo conocido MAS LARGO de marca + titulo (FragranceSense guarda brand="CAROLINA").
 *  Si no resuelve: null + marca parecida sugerida (Jaro-Winkler >= 0.9 sobre la marca sin espacios).
 */
public final class NsoBrandDictionary {

    public static final String VIA_ADMIN = "ADMIN";
    public static final String VIA_EXACT = "EXACT";
    public static final String VIA_ALIAS = "ALIAS";
    public static final String VIA_COMPACT = "COMPACT";
    public static final String VIA_CONTAINS = "CONTAINS";
    public static final String VIA_PREFIX = "PREFIX";

    /** Similitud minima para sugerir "esta marca es la misma que...". */
    public static final double SUGGEST_MIN_JW = 0.9;

    /**
     * Alias fijos: cada grupo son nombres de la MISMA marca. Al construir, la canonica del grupo es el primer
     * miembro que exista en el catalogo (o el primero de la lista si ninguno existe).
     */
    static final List<List<String>> FIXED_GROUPS = List.of(
            List.of("christian dior", "dior"),
            List.of("paco rabanne", "rabanne"),
            List.of("yves saint laurent", "ysl", "saint laurent"),
            List.of("giorgio armani", "armani", "emporio armani"),
            List.of("hugo boss", "boss"),
            List.of("antonio banderas", "banderas"),
            List.of("salvatore ferragamo", "ferragamo"),
            List.of("afnan", "zimaya afnan", "zimaya", "afnan perfumes"),
            // Sub-lineas de Lattafa: "LATTAFA PRIDE SHAHEEN GOLD" es "SPRAY SHAHEEN GOLD" en aduanas.
            List.of("lattafa", "lattafa pride", "lattafa niche", "niche emarati", "lattafa perfumes"),
            List.of("dolce and gabbana", "dolce gabbana", "dolce and gabanna", "d and g", "dg"),
            List.of("carolina herrera", "ch"),
            List.of("jean paul gaultier", "jpg", "gaultier"),
            List.of("calvin klein", "ck"),
            List.of("montblanc", "mont blanc"),
            List.of("maison alhambra", "alhambra"),
            List.of("french avenue", "fragrance world", "fragance world"),
            List.of("viktor and rolf", "viktor rolf"),
            List.of("bvlgari", "bulgari"),
            List.of("donna karan", "dkny"),
            List.of("tommy hilfiger", "tommy"),
            List.of("mugler", "thierry mugler"),
            List.of("parfums de marly", "de marly"),
            List.of("roja", "roja dove", "roja parfums"),
            List.of("louis vuitton", "lv")
    );

    /** Palabras que parecen abreviatura de marca pero son de producto (212, miss, coco...). */
    static final Set<String> ABBREVIATION_BLACKLIST = Set.of(
            "212", "miss", "coco", "n5", "eros", "set", "man", "tous", "nina", "club", "the", "la", "le", "l",
            "agua", "eau", "edp", "edt", "edc", "new", "les", "my", "one", "gift", "kit", "tt");

    /** Minimo de registros de la marca que deben empezar con la abreviatura para aprenderla. */
    static final int ABBREVIATION_MIN_RECORDS = 3;

    // canonica -> nombre para mostrar (marca del catalogo tal cual)
    private final Map<String, String> display = new TreeMap<>();
    // brandKey del catalogo -> canonica
    private final Map<String, String> catalogToCanonical = new TreeMap<>();
    // frase conocida (catalogo o alias fijo) -> canonica
    private final Map<String, String> nameToCanonical = new HashMap<>();
    // frase sin espacios -> canonica
    private final Map<String, String> compactToCanonical = new HashMap<>();
    // alias BRAND de la admin: marca del proveedor (plegada) -> canonica
    private final Map<String, String> adminAliases = new HashMap<>();
    // abreviatura aprendida -> canonica
    private final Map<String, String> abbreviations = new TreeMap<>();
    // canonica -> frases y abreviaturas que se quitan de los nombres
    private final Map<String, Set<String>> terms = new HashMap<>();
    // canonicas que tienen registros en el catalogo
    private final Set<String> inCatalog = new TreeSet<>();
    private int maxPhraseWords = 1;

    private NsoBrandDictionary() {}

    // ===================== construccion =====================

    /**
     * Construye el diccionario desde los registros del catalogo (normalmente los activos) y los alias BRAND
     * de la admin (se ignoran los alias de otro tipo). Determinista: el orden de entrada no importa.
     */
    public static NsoBrandDictionary build(Collection<NsoRecord> records, Collection<NsoAlias> brandAliases) {
        NsoBrandDictionary d = new NsoBrandDictionary();
        List<NsoRecord> sorted = new ArrayList<>();
        if (records != null) {
            for (NsoRecord r : records) if (r != null && r.getCode() != null) sorted.add(r);
        }
        sorted.sort(Comparator.comparing(NsoRecord::getCode));

        // 1. Marcas del catalogo.
        Map<String, String> catalogDisplay = new TreeMap<>();
        for (NsoRecord r : sorted) {
            String bk = recordBrandKey(r);
            if (bk.isEmpty()) continue;
            catalogDisplay.putIfAbsent(bk, r.getBrand() == null ? bk.toUpperCase() : r.getBrand().trim());
        }

        // 2. Grupos fijos: canonica = primer miembro presente en el catalogo.
        Map<String, String> memberToCanonical = new HashMap<>();
        for (List<String> group : FIXED_GROUPS) {
            String canonical = null;
            for (String m : group) {
                if (catalogDisplay.containsKey(m)) { canonical = m; break; }
            }
            if (canonical == null) canonical = group.get(0);
            for (String m : group) memberToCanonical.put(m, canonical);
        }
        for (Map.Entry<String, List<String>> e : groupMembersByCanonical(memberToCanonical).entrySet()) {
            for (String m : e.getValue()) d.register(m, e.getKey());
        }
        for (Map.Entry<String, String> e : catalogDisplay.entrySet()) {
            String canonical = memberToCanonical.getOrDefault(e.getKey(), e.getKey());
            d.catalogToCanonical.put(e.getKey(), canonical);
            d.inCatalog.add(canonical);
            d.register(e.getKey(), canonical);
            // Nombre para mostrar: el de la canonica si esta en el catalogo, si no el primero que aparezca.
            if (e.getKey().equals(canonical) || !d.display.containsKey(canonical)) {
                d.display.put(canonical, e.getValue());
            }
        }
        for (List<String> group : FIXED_GROUPS) {
            String canonical = memberToCanonical.get(group.get(0));
            d.display.putIfAbsent(canonical, canonical.toUpperCase());
        }

        // 3. Abreviaturas aprendidas: primera palabra del nombre declarado.
        Map<String, Map<String, Integer>> firstWordCounts = new TreeMap<>();
        for (NsoRecord r : sorted) {
            String canonical = d.catalogToCanonical.get(recordBrandKey(r));
            if (canonical == null) continue;
            String f = NsoKeys.fold(r.getDeclaredName());
            if (f.isEmpty()) continue;
            String first = f.split(" ")[0];
            firstWordCounts.computeIfAbsent(first, k -> new TreeMap<>()).merge(canonical, 1, Integer::sum);
        }
        for (Map.Entry<String, Map<String, Integer>> e : firstWordCounts.entrySet()) {
            String w = e.getKey();
            Map<String, Integer> byBrand = e.getValue();
            if (byBrand.size() != 1) continue; // nunca primera palabra en otra marca
            String canonical = byBrand.keySet().iterator().next();
            int count = byBrand.get(canonical);
            if (count < ABBREVIATION_MIN_RECORDS) continue;
            if (w.length() < 2 || w.length() > 4) continue;
            if (!w.chars().allMatch(Character::isLetter)) continue;
            if (ABBREVIATION_BLACKLIST.contains(w)) continue;
            if (!d.initialsOf(canonical).contains(w.charAt(0))) continue; // "IRR", "OMN", "PRED" son de producto
            d.abbreviations.put(w, canonical);
            d.terms.computeIfAbsent(canonical, k -> new TreeSet<>()).add(w);
        }

        // 4. Alias BRAND de la admin.
        if (brandAliases != null) {
            List<NsoAlias> aliases = new ArrayList<>();
            for (NsoAlias a : brandAliases) {
                if (a != null && NsoAlias.KIND_BRAND.equals(a.getKind()) && a.getAliasKey() != null
                        && a.getTargetBrandKey() != null && a.isPositive()) aliases.add(a);
            }
            aliases.sort(Comparator.comparing(NsoAlias::getAliasKey)
                    .thenComparing(NsoAlias::getTargetBrandKey));
            for (NsoAlias a : aliases) {
                String from = NsoKeys.brandKey(a.getAliasKey());
                String target = d.canonicalKey(a.getTargetBrandKey());
                if (from.isEmpty() || target == null || target.isEmpty()) continue;
                d.adminAliases.putIfAbsent(from, target);
                d.adminAliases.putIfAbsent(from.replace(" ", ""), target);
            }
        }
        return d;
    }

    private static Map<String, List<String>> groupMembersByCanonical(Map<String, String> memberToCanonical) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(memberToCanonical).entrySet()) {
            out.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        return out;
    }

    private void register(String phrase, String canonical) {
        if (phrase == null || phrase.isEmpty()) return;
        nameToCanonical.putIfAbsent(phrase, canonical);
        compactToCanonical.putIfAbsent(phrase.replace(" ", ""), canonical);
        terms.computeIfAbsent(canonical, k -> new TreeSet<>()).add(phrase);
        maxPhraseWords = Math.max(maxPhraseWords, phrase.split(" ").length);
    }

    private Set<Character> initialsOf(String canonical) {
        Set<Character> out = new LinkedHashSet<>();
        for (String phrase : terms.getOrDefault(canonical, Set.of())) {
            for (String w : phrase.split(" ")) if (!w.isEmpty()) out.add(w.charAt(0));
        }
        return out;
    }

    private static String recordBrandKey(NsoRecord r) {
        String bk = r.getBrandKey();
        return bk != null && !bk.isBlank() ? NsoKeys.brandKey(bk) : NsoKeys.brandKey(r.getBrand());
    }

    // ===================== consulta =====================

    /** Resultado de resolver una marca. */
    public static final class BrandMatch {
        /** Marca canonica del catalogo, o null si no se reconocio. */
        public final String brandKey;
        /** Como se resolvio (VIA_*), o null. */
        public final String via;
        /** Frase de marca que se reconocio en el texto ("carolina herrera"), o null. */
        public final String matchedPhrase;
        /** Si no se reconocio: marca del catalogo parecida (clave canonica) para sugerir, o null. */
        public final String suggestedBrandKey;
        /** Nombre para mostrar de la marca sugerida, o null. */
        public final String suggestedBrand;

        BrandMatch(String brandKey, String via, String matchedPhrase, String suggestedBrandKey, String suggestedBrand) {
            this.brandKey = brandKey;
            this.via = via;
            this.matchedPhrase = matchedPhrase;
            this.suggestedBrandKey = suggestedBrandKey;
            this.suggestedBrand = suggestedBrand;
        }

        public boolean isResolved() { return brandKey != null; }
    }

    /**
     * Resuelve la marca de un producto a partir de la columna marca del proveedor y sus titulos.
     * Los titulos solo se usan para el prefijo (marca cortada en la primera palabra, o marca vacia).
     */
    public BrandMatch resolve(String brandText, String... titles) {
        List<String> ts = new ArrayList<>();
        if (titles != null) for (String t : titles) if (t != null && !t.isBlank()) ts.add(t);
        return resolve(brandText, ts);
    }

    /** Igual que resolve(String, String...) con una coleccion de titulos. */
    public BrandMatch resolve(String brandText, Collection<String> titles) {
        String b = NsoKeys.brandKey(brandText);
        if (!b.isEmpty()) {
            // 1. alias de la admin
            String admin = adminAliases.get(b);
            if (admin == null) admin = adminAliases.get(b.replace(" ", ""));
            if (admin != null) return new BrandMatch(admin, VIA_ADMIN, b, null, null);
            // 2. exacta / alias fijo / sin espacios
            String exact = nameToCanonical.get(b);
            if (exact != null) {
                String via = catalogToCanonical.containsKey(b) ? VIA_EXACT : VIA_ALIAS;
                return new BrandMatch(exact, via, b, null, null);
            }
            String compact = compactToCanonical.get(b.replace(" ", ""));
            if (compact != null) return new BrandMatch(compact, VIA_COMPACT, b, null, null);
            // 3. contencion de palabra completa (la frase mas larga)
            String bestPhrase = null;
            String padded = " " + b + " ";
            for (Map.Entry<String, String> e : nameToCanonical.entrySet()) {
                String p = e.getKey();
                if (p.length() < 3 || p.equals(b)) continue;
                // Solo marcas del catalogo o alias de varias palabras: "Tommy Bahama" no es "tommy" (Hilfiger).
                if (!catalogToCanonical.containsKey(p) && !p.contains(" ")) continue;
                if (!padded.contains(" " + p + " ")) continue;
                if (bestPhrase == null || p.length() > bestPhrase.length()
                        || (p.length() == bestPhrase.length() && p.compareTo(bestPhrase) < 0)) bestPhrase = p;
            }
            if (bestPhrase != null) {
                return new BrandMatch(nameToCanonical.get(bestPhrase), VIA_CONTAINS, bestPhrase, null, null);
            }
        }
        // 4. prefijo conocido mas largo de marca + titulo (o del titulo solo)
        String bestPrefix = null;
        String bestCanonical = null;
        List<String> texts = new ArrayList<>();
        for (String t : titles == null ? List.<String>of() : titles) {
            String ft = NsoKeys.fold(t);
            if (ft.isEmpty()) continue;
            // Con marca escrita: marca + titulo ("CAROLINA" + "HERRERA GOOD GIRL"). Sin marca: el titulo solo.
            texts.add(b.isEmpty() ? ft : b + " " + ft);
        }
        // Con marca escrita que no se reconocio, el prefijo debe ir MAS ALLA de esa marca: "CAROLINA" + "HERRERA..."
        // si; "Tommy Bahama" -> "tommy" no (eso seria contencion de una sola palabra de alias).
        int minK = b.isEmpty() ? 1 : b.split(" ").length + 1;
        for (String text : texts) {
            String[] words = text.split(" ");
            int maxK = Math.min(words.length, maxPhraseWords);
            for (int k = maxK; k >= minK; k--) {
                String phrase = String.join(" ", java.util.Arrays.copyOfRange(words, 0, k));
                String c = nameToCanonical.get(phrase);
                if (c == null) c = compactToCanonical.get(phrase.replace(" ", ""));
                if (c == null || phrase.length() < 2) continue;
                // Una sola letra/abreviatura corta suelta al inicio del titulo no basta ("ch", "dg", "lv").
                if (k == 1 && phrase.length() <= 3 && !catalogToCanonical.containsKey(phrase)) continue;
                if (bestPrefix == null || phrase.length() > bestPrefix.length()) {
                    bestPrefix = phrase;
                    bestCanonical = c;
                }
                break;
            }
        }
        if (bestCanonical != null) return new BrandMatch(bestCanonical, VIA_PREFIX, bestPrefix, null, null);

        // 5. sin marca: sugerir una parecida
        String suggestion = suggest(b);
        return new BrandMatch(null, null, null, suggestion, suggestion == null ? null : display.get(suggestion));
    }

    /** Marca canonica parecida (Jaro-Winkler >= 0.9 sin espacios) o null. */
    public String suggest(String brandText) {
        String c = NsoKeys.brandCompact(brandText);
        if (c.length() < 3) return null;
        String best = null;
        double bestScore = 0;
        for (Map.Entry<String, String> e : new TreeMap<>(compactToCanonical).entrySet()) {
            if (e.getKey().length() < 3) continue;
            double s = NsoText.jaroWinkler(c, e.getKey());
            // Ademas de parecerse, debe ser un typo corto o un prefijo: "LATTAF" -> LATTAFA si,
            // "Elizabeth Taylor" -> ELIZABETH ARDEN no.
            boolean close = e.getKey().startsWith(c) || c.startsWith(e.getKey()) || NsoText.levenshtein(c, e.getKey()) <= 2;
            if (close && s >= SUGGEST_MIN_JW && s > bestScore) {
                bestScore = s;
                best = e.getValue();
            }
        }
        return best;
    }

    /**
     * Canonica de una marca cualquiera (brandKey del catalogo, alias fijo o texto libre). Si no se conoce,
     * devuelve la marca plegada tal cual (para guardar en ProductNso.brandKey y buscar por marca despues).
     */
    public String canonicalKey(String brand) {
        String b = NsoKeys.brandKey(brand);
        if (b.isEmpty()) return "";
        String c = adminAliases.get(b);
        if (c != null) return c;
        c = nameToCanonical.get(b);
        if (c != null) return c;
        c = compactToCanonical.get(b.replace(" ", ""));
        return c != null ? c : b;
    }

    /** True si la marca canonica tiene registros en el catalogo. */
    public boolean hasCatalogRecords(String canonicalKey) {
        return canonicalKey != null && inCatalog.contains(canonicalKey);
    }

    /** Frases de marca y abreviaturas aprendidas a quitar de los nombres de esa marca canonica. */
    public Set<String> brandTerms(String canonicalKey) {
        Set<String> t = terms.get(canonicalKey);
        return t == null ? Set.of() : Collections.unmodifiableSet(t);
    }

    /** Nombre para mostrar de una marca canonica (como viene en el catalogo). */
    public String displayName(String canonicalKey) {
        if (canonicalKey == null) return null;
        String d = display.get(canonicalKey);
        return d != null ? d : canonicalKey.toUpperCase();
    }

    /** Abreviaturas aprendidas del catalogo (abreviatura -> marca canonica). */
    public Map<String, String> learnedAbbreviations() {
        return Collections.unmodifiableMap(abbreviations);
    }

    /** Marcas canonicas con registros en el catalogo, ordenadas. */
    public Set<String> catalogBrandKeys() {
        return Collections.unmodifiableSet(inCatalog);
    }

    /** Todas las brandKey del catalogo que caen en una canonica (ej. "christian dior" -> [christian dior, dior]). */
    public List<String> catalogKeysOf(String canonicalKey) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : catalogToCanonical.entrySet()) {
            if (e.getValue().equals(canonicalKey)) out.add(e.getKey());
        }
        return out;
    }

    /** Solo para depurar/tests. */
    Map<String, String> adminAliasesView() {
        return new LinkedHashMap<>(adminAliases);
    }
}
