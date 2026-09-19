package org.example.backendbvaberiaperfumes.service.nso;

import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.example.backendbvaberiaperfumes.model.NsoCandidate;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.example.backendbvaberiaperfumes.service.nso.NsoBrandDictionary.BrandMatch;
import org.example.backendbvaberiaperfumes.service.nso.NsoNormalizer.NormalizedName;

import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reconocimiento NSO: dice si un perfume tiene Notificacion Sanitaria y con que codigo (puro, determinista,
 * explicable; sin Spring ni BD).
 *
 * Uso:
 * <pre>
 *   NsoMatcher.Index index = NsoMatcher.Index.builder()
 *           .records(recordRepo.findByActiveTrue())
 *           .aliases(aliasRepo.findAll())
 *           .rejectedPairs(...)            // productId -> codigos REJECTED
 *           .siblings(evidenciasDeProductosActivos)
 *           .acceptCanCodes(true).reviewMinScore(0.66)
 *           .build();                       // UNA vez por sesion
 *   NsoMatcher.Result r = new NsoMatcher(index).resolve(evidence);
 * </pre>
 *
 * Orden de resolve (especificacion NSO, seccion 4):
 *  0. decision bloqueada de la admin (si el codigo sigue activo);
 *  1. GTIN == EAN del registro o alias GTIN aprobado (misma marca -> CON_NSO; otra marca -> revision);
 *  2. alias SKU del proveedor o NAME_KEY aprobados -> CON_NSO (dos codigos distintos -> revision);
 *     alias NEGATIVE y pares rechazados eliminan codigos de todo lo que sigue;
 *  3. nombre dentro de la marca: automatico SOLO con identidad exacta (sin palabras sobrantes y sin conflictos);
 *  4. hasta 3 candidatos -> EN_REVISION;  5. marca con registros -> MARCA_CON_NSO;  6. SIN_NSO;
 *  7. la investigacion anterior solo agrega candidatos (nunca asigna).
 * El puntaje (0.75 dice + 0.25 Jaro-Winkler) solo ordena y se muestra: nunca decide un automatico.
 */
public final class NsoMatcher {

    public static final int MAX_CANDIDATES = 3;
    public static final double DEFAULT_REVIEW_MIN_SCORE = 0.66;
    public static final String PERU = "PE";

    /** Antonimos: si uno sobra de un lado y el otro del otro lado, son perfumes distintos (nunca candidato). */
    private static final Map<String, String> ANTONYMS = Map.ofEntries(
            Map.entry("king", "queen"), Map.entry("queen", "king"),
            Map.entry("him", "her"), Map.entry("her", "him"),
            Map.entry("day", "night"), Map.entry("night", "day"),
            Map.entry("black", "white"), Map.entry("white", "black"),
            Map.entry("sun", "moon"), Map.entry("moon", "sun"),
            Map.entry("homme", "femme"), Map.entry("femme", "homme"));

    /** Pares a distancia 1 que son palabras distintas de verdad (no typos). */
    private static final Set<String> TYPO_BLOCK = Set.of("light|night", "night|light");

    private final Index index;

    public NsoMatcher(Index index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public Index index() {
        return index;
    }

    // =====================================================================
    // Evidencia
    // =====================================================================

    /** Oferta de un proveedor para el producto. */
    public static final class Offer {
        public final String supplierName;
        public final String supplierSku;
        public final String rawTitle;
        public final String gtin;

        public Offer(String supplierName, String supplierSku, String rawTitle, String gtin) {
            this.supplierName = supplierName;
            this.supplierSku = supplierSku;
            this.rawTitle = rawTitle;
            this.gtin = gtin;
        }
    }

    /** Todo lo que se sabe de un producto (o de una fila de preview). Mutable, con setters encadenables. */
    public static final class Evidence {
        public Long productId;
        public String brand;
        public String name;
        public String type;
        public String category;
        /** Product.forma: single | set | oil | deo (o perfume/body). */
        public String forma;
        public Integer ml;
        public String gtin;
        public final List<Offer> offers = new ArrayList<>();
        /** Decision de la admin (ProductNso.locked). */
        public boolean locked;
        public String lockedStatus;
        public String lockedCode;
        public String lockedMatchedBy;
        /** Codigos sugeridos por la investigacion (hoja "Con NSO"), codigo -> detalle ("ALTA · producto"). */
        public final Map<String, String> researchCodes = new LinkedHashMap<>();
        /** Codigos que la admin ya rechazo para este producto (nso_candidates REJECTED). */
        public final Set<String> rejectedCodes = new LinkedHashSet<>();
        /** null = usar el valor del indice (nso_accept_can_codes). */
        public Boolean acceptCanCodes;

        public Evidence() {}

        public static Evidence of(Long productId, String brand, String name) {
            Evidence e = new Evidence();
            e.productId = productId;
            e.brand = brand;
            e.name = name;
            return e;
        }

        public Evidence offer(String supplierName, String supplierSku, String rawTitle, String gtin) {
            offers.add(new Offer(supplierName, supplierSku, rawTitle, gtin));
            return this;
        }

        public Evidence type(String type) { this.type = type; return this; }
        public Evidence category(String category) { this.category = category; return this; }
        public Evidence forma(String forma) { this.forma = forma; return this; }
        public Evidence ml(Integer ml) { this.ml = ml; return this; }
        public Evidence gtin(String gtin) { this.gtin = gtin; return this; }

        /** Decision bloqueada: status (CON_NSO...), codigo y matchedBy guardados. */
        public Evidence locked(String status, String code, String matchedBy) {
            this.locked = true;
            this.lockedStatus = status;
            this.lockedCode = code;
            this.lockedMatchedBy = matchedBy;
            return this;
        }

        public Evidence research(String code, String detail) {
            if (code != null) researchCodes.put(code, detail);
            return this;
        }

        public Evidence rejected(String code) {
            if (code != null) rejectedCodes.add(code);
            return this;
        }

        public Evidence acceptCanCodes(Boolean accept) { this.acceptCanCodes = accept; return this; }
    }

    // =====================================================================
    // Resultado
    // =====================================================================

    /** Opcion para la cola de revision. */
    public static final class Candidate {
        public final String code;
        public final double score;
        public final List<String> reasons;
        /** MATCHER | RESEARCH (NsoCandidate.ORIGIN_*) */
        public final String origin;
        /** Mismas palabras que el nombre declarado (aunque tenga conflictos). */
        public final boolean exactName;

        Candidate(String code, double score, List<String> reasons, String origin, boolean exactName) {
            this.code = code;
            this.score = score;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
            this.origin = origin;
            this.exactName = exactName;
        }

        public String getCode() { return code; }
        public double getScore() { return score; }
        public List<String> getReasons() { return reasons; }
        public String getOrigin() { return origin; }
        public boolean isExactName() { return exactName; }

        @Override
        public String toString() {
            return code + "(" + score + "," + origin + ")" + reasons;
        }
    }

    /** Titular que ya trae la marca (para "la marca tiene NSO pero falta este producto"). */
    public static final class Titular {
        public final String titular;
        public final String ruc;
        public final List<String> codes;

        Titular(String titular, String ruc, List<String> codes) {
            this.titular = titular;
            this.ruc = ruc;
            this.codes = Collections.unmodifiableList(new ArrayList<>(codes));
        }

        public String getTitular() { return titular; }
        public String getRuc() { return ruc; }
        public List<String> getCodes() { return codes; }
    }

    /** Decision del matcher para un producto. */
    public static final class Result {
        /** CON_NSO | EN_REVISION | MARCA_CON_NSO | SIN_NSO (ProductNso.STATUS_*) */
        public String status;
        public String nsoCode;
        /** UPC | ALIAS_SKU | ALIAS_NOMBRE | NOMBRE | MANUAL | APROBADO, o null. */
        public String matchedBy;
        public Double score;
        public final List<String> reasons = new ArrayList<>();
        public final List<Candidate> candidates = new ArrayList<>();
        /** Marca canonica (o la marca plegada si no esta en el catalogo). */
        public String brandKey;
        /** Marca del catalogo parecida cuando la del producto no se reconocio. */
        public String suggestedBrand;
        public String suggestedBrandKey;
        /** Titulares de la marca en el catalogo (vacio si la marca no tiene registros). */
        public final List<Titular> brandTitulares = new ArrayList<>();
        /** Registros de la marca que son solo genericos ("AGUA DE PERFUME"). */
        public int genericRecords;
        /** Codigos de la marca sin titular conocido (filas Aduanet). */
        public int unknownTitularCodes;
        /** NAME_KEY del producto (para guardar el alias al aprobar). */
        public String nameKey;
        /** Si es CON_NSO por nombre: los otros codigos con el mismo nombre declarado (renovaciones). */
        public final Set<String> renewalCodes = new TreeSet<>();

        public String getStatus() { return status; }
        public String getNsoCode() { return nsoCode; }
        public String getMatchedBy() { return matchedBy; }
        public Double getScore() { return score; }
        public List<String> getReasons() { return reasons; }
        public List<Candidate> getCandidates() { return candidates; }
        public String getBrandKey() { return brandKey; }
        public String getSuggestedBrand() { return suggestedBrand; }
        public String getSuggestedBrandKey() { return suggestedBrandKey; }
        public List<Titular> getBrandTitulares() { return brandTitulares; }
        public int getGenericRecords() { return genericRecords; }
        public int getUnknownTitularCodes() { return unknownTitularCodes; }
        public String getNameKey() { return nameKey; }
        public Set<String> getRenewalCodes() { return renewalCodes; }

        public boolean isConNso() { return ProductNso.STATUS_CON_NSO.equals(status); }

        void addReason(String r) {
            if (r != null && !r.isBlank() && !reasons.contains(r)) reasons.add(r);
        }

        @Override
        public String toString() {
            return status + " " + nsoCode + " by=" + matchedBy + " score=" + score + " " + reasons + " cands=" + candidates;
        }
    }

    // =====================================================================
    // Indice (inmutable, una vez por sesion)
    // =====================================================================

    /** Registro preparado: normalizado una sola vez. */
    static final class Entry {
        final NsoRecord record;
        final String code;
        final String canonicalBrand;
        final NormalizedName norm;
        final String country;
        final int year;
        final int importKey;

        Entry(NsoRecord record, String canonicalBrand, NormalizedName norm, int currentYear) {
            this.record = record;
            this.code = record.getCode();
            this.canonicalBrand = canonicalBrand;
            this.norm = norm;
            String c = record.getCountry() != null && !record.getCountry().isBlank()
                    ? record.getCountry().trim().toUpperCase(Locale.ROOT) : NsoCode.country(record.getCode());
            this.country = c;
            Integer y = record.getNsoYear() != null ? record.getNsoYear() : NsoCode.year(record.getCode(), currentYear);
            this.year = y == null ? 0 : y;
            this.importKey = NsoKeys.dateSortKey(record.getLastImportDate());
        }

        boolean isPeru() { return PERU.equals(country); }
    }

    /** Atributos de un producto hermano (misma marca y mismo nombre). */
    static final class SiblingAttr {
        final String gender;
        final String concentration;
        final String forma;

        SiblingAttr(String gender, String concentration, String forma) {
            this.gender = gender;
            this.concentration = concentration;
            this.forma = forma;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SiblingAttr s)) return false;
            return Objects.equals(gender, s.gender) && Objects.equals(concentration, s.concentration)
                    && Objects.equals(forma, s.forma);
        }

        @Override
        public int hashCode() { return Objects.hash(gender, concentration, forma); }
    }

    /** Pista de la investigacion (alias RESEARCH). */
    static final class ResearchHint {
        final String code;
        final String detail;

        ResearchHint(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }
    }

    public static final class Index {
        final NsoBrandDictionary brands;
        final Map<String, Entry> entriesByCode;
        final Map<String, List<Entry>> entriesByBrand;
        final Map<String, Set<String>> eanToCodes;
        final Map<String, Set<String>> positiveAliases;
        final Map<String, Set<String>> negativeAliases;
        final Map<String, List<ResearchHint>> researchAliases;
        final Map<Long, Set<String>> rejectedPairs;
        final Map<String, Set<SiblingAttr>> siblings;
        /** Mismo indice que siblings pero con los registros NSO con genero (marca + nombre -> atributos). */
        final Map<String, Set<SiblingAttr>> recordSiblings;
        final boolean acceptCanCodes;
        final double reviewMinScore;
        final int currentYear;

        private Index(Builder b) {
            this.acceptCanCodes = b.acceptCanCodes;
            this.reviewMinScore = b.reviewMinScore;
            this.currentYear = b.currentYear;

            // Registros activos, ordenados por codigo (determinismo).
            Map<String, NsoRecord> active = new TreeMap<>();
            for (NsoRecord r : b.records) {
                if (r == null || r.getCode() == null) continue;
                if (Boolean.FALSE.equals(r.getActive())) continue;
                active.putIfAbsent(r.getCode(), r);
            }
            List<NsoAlias> brandAliases = new ArrayList<>();
            for (NsoAlias a : b.aliases) {
                if (a != null && NsoAlias.KIND_BRAND.equals(a.getKind())) brandAliases.add(a);
            }
            this.brands = NsoBrandDictionary.build(active.values(), brandAliases);

            Map<String, Entry> byCode = new TreeMap<>();
            Map<String, List<Entry>> byBrand = new TreeMap<>();
            Map<String, Set<String>> ean = new HashMap<>();
            for (NsoRecord r : active.values()) {
                String bk = r.getBrandKey() != null && !r.getBrandKey().isBlank() ? r.getBrandKey() : r.getBrand();
                String canonical = brands.canonicalKey(bk);
                NormalizedName norm = NsoNormalizer.normalizeDeclared(r.getDeclaredName(), brands.brandTerms(canonical));
                Entry e = new Entry(r, canonical, norm, currentYear);
                byCode.put(e.code, e);
                byBrand.computeIfAbsent(canonical, k -> new ArrayList<>()).add(e);
                String g = NsoKeys.gtinKey(r.getEan());
                if (g != null) ean.computeIfAbsent(g, k -> new TreeSet<>()).add(e.code);
            }
            this.entriesByCode = Collections.unmodifiableMap(byCode);
            // Hermanos DENTRO del catalogo NSO (misma marca y mismo nombre): "QIMMAH EDP L" y "QIMMAH EDP M" dicen
            // que existen las dos versiones aunque la lista del proveedor de este mes traiga solo una.
            Map<String, Set<SiblingAttr>> recSib = new HashMap<>();
            for (Entry e : byCode.values()) {
                if (e.norm.isGeneric() || e.norm.gender == null) continue;
                recSib.computeIfAbsent(siblingKey(e.canonicalBrand, e.norm.sortedCompact()), k -> new HashSet<>())
                        .add(new SiblingAttr(e.norm.gender, e.norm.concentration, e.norm.forma));
            }
            this.recordSiblings = recSib;
            byBrand.replaceAll((k, v) -> Collections.unmodifiableList(v));
            this.entriesByBrand = Collections.unmodifiableMap(byBrand);
            this.eanToCodes = ean;

            Map<String, Set<String>> pos = new HashMap<>();
            Map<String, Set<String>> neg = new HashMap<>();
            Map<String, List<ResearchHint>> research = new HashMap<>();
            List<NsoAlias> sortedAliases = new ArrayList<>(b.aliases);
            sortedAliases.removeIf(a -> a == null || a.getKind() == null || a.getAliasKey() == null
                    || NsoAlias.KIND_BRAND.equals(a.getKind()));
            sortedAliases.sort(Comparator.comparing(NsoAlias::getKind).thenComparing(NsoAlias::getAliasKey)
                    .thenComparing(a -> NsoAlias.codeKeyOf(a.getNsoCode())));
            for (NsoAlias a : sortedAliases) {
                String key = aliasMapKey(a.getKind(), a.getAliasKey());
                String code = NsoAlias.codeKeyOf(a.getNsoCode());
                if (a.isResearch()) {
                    if (a.isPositive() && !NsoAlias.NO_CODE.equals(code)) {
                        research.computeIfAbsent(key, k -> new ArrayList<>()).add(new ResearchHint(code, a.getDetail()));
                    }
                } else if (a.isPositive()) {
                    if (!NsoAlias.NO_CODE.equals(code)) pos.computeIfAbsent(key, k -> new TreeSet<>()).add(code);
                } else {
                    neg.computeIfAbsent(key, k -> new TreeSet<>()).add(code);
                }
            }
            this.positiveAliases = pos;
            this.negativeAliases = neg;
            this.researchAliases = research;

            Map<Long, Set<String>> rejected = new HashMap<>();
            for (Map.Entry<Long, Set<String>> e : b.rejected.entrySet()) {
                rejected.put(e.getKey(), Collections.unmodifiableSet(new TreeSet<>(e.getValue())));
            }
            this.rejectedPairs = rejected;

            // Hermanos: marca + nombre -> generos/concentraciones/formas de productos activos.
            Map<String, Set<SiblingAttr>> sib = new HashMap<>();
            for (Evidence ev : b.siblings) {
                if (ev == null) continue;
                Prepared p = prepare(this.brands, ev);
                if (p.canonical == null || !p.brandResolved) continue;
                SiblingAttr attr = new SiblingAttr(p.gender, p.concentration, p.forma);
                for (NormalizedName t : p.texts) {
                    sib.computeIfAbsent(siblingKey(p.canonical, t.sortedCompact()), k -> new HashSet<>()).add(attr);
                }
            }
            this.siblings = sib;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private final List<NsoRecord> records = new ArrayList<>();
            private final List<NsoAlias> aliases = new ArrayList<>();
            private final Map<Long, Set<String>> rejected = new HashMap<>();
            private final List<Evidence> siblings = new ArrayList<>();
            private boolean acceptCanCodes = true;
            private double reviewMinScore = DEFAULT_REVIEW_MIN_SCORE;
            private int currentYear = Year.now().getValue();

            private Builder() {}

            /** Registros del catalogo (los inactivos se ignoran). */
            public Builder records(Collection<NsoRecord> rs) {
                if (rs != null) records.addAll(rs);
                return this;
            }

            /** Alias de todos los tipos (GTIN, SUPPLIER_SKU, NAME_KEY, BRAND; POSITIVE/NEGATIVE; RESEARCH). */
            public Builder aliases(Collection<NsoAlias> as) {
                if (as != null) aliases.addAll(as);
                return this;
            }

            /** Par (producto, codigo) que la admin rechazo. */
            public Builder rejectedPair(Long productId, String code) {
                if (productId != null && code != null) rejected.computeIfAbsent(productId, k -> new TreeSet<>()).add(code);
                return this;
            }

            public Builder rejectedPairs(Map<Long, ? extends Collection<String>> pairs) {
                if (pairs != null) pairs.forEach((pid, codes) -> {
                    if (codes != null) codes.forEach(c -> rejectedPair(pid, c));
                });
                return this;
            }

            /** Productos activos (y filas de preview) para la regla "existe version hombre y mujer". */
            public Builder siblings(Collection<Evidence> evidences) {
                if (evidences != null) siblings.addAll(evidences);
                return this;
            }

            public Builder acceptCanCodes(boolean accept) {
                this.acceptCanCodes = accept;
                return this;
            }

            public Builder reviewMinScore(double min) {
                this.reviewMinScore = min;
                return this;
            }

            public Builder currentYear(int year) {
                this.currentYear = year;
                return this;
            }

            public Index build() {
                return new Index(this);
            }
        }

        public NsoBrandDictionary brands() { return brands; }

        /** Registro ACTIVO por codigo, o null. */
        public NsoRecord record(String code) {
            Entry e = code == null ? null : entriesByCode.get(code);
            return e == null ? null : e.record;
        }

        public boolean isActiveCode(String code) {
            return code != null && entriesByCode.containsKey(code);
        }

        public int activeRecordCount() { return entriesByCode.size(); }

        public boolean isEmpty() { return entriesByCode.isEmpty(); }

        public boolean acceptCanCodes() { return acceptCanCodes; }

        public double reviewMinScore() { return reviewMinScore; }

        /** Marca canonica de una brandKey del catalogo o de texto libre (para ProductNso.brandKey). */
        public String canonicalBrandKey(String brand) { return brands.canonicalKey(brand); }

        /** Registros activos de una marca canonica, ordenados por codigo. */
        public List<NsoRecord> recordsOfBrand(String canonicalBrandKey) {
            List<NsoRecord> out = new ArrayList<>();
            for (Entry e : entriesByBrand.getOrDefault(canonicalBrandKey, List.of())) out.add(e.record);
            return out;
        }

        /** True si el registro es solo generico ("AGUA DE PERFUME"): nunca coincide por nombre. */
        public boolean isGenericRecord(String code) {
            Entry e = code == null ? null : entriesByCode.get(code);
            return e != null && e.norm.isGeneric();
        }

        Set<String> aliasCodes(Map<String, Set<String>> map, String kind, Collection<String> keys) {
            Set<String> out = new TreeSet<>();
            for (String k : keys) {
                if (k == null) continue;
                Set<String> c = map.get(aliasMapKey(kind, k));
                if (c != null) out.addAll(c);
            }
            return out;
        }
    }

    static String aliasMapKey(String kind, String aliasKey) {
        return kind + "#" + aliasKey;
    }

    static String siblingKey(String canonical, String sortedCompact) {
        return canonical + NsoKeys.SEP + sortedCompact;
    }

    // =====================================================================
    // Preparacion de la evidencia
    // =====================================================================

    /** Evidencia normalizada: marca resuelta, textos con palabras y atributos combinados. */
    static final class Prepared {
        BrandMatch brandMatch;
        boolean brandResolved;
        /** Canonica si se resolvio; si no, la marca plegada (o null si no hay marca). */
        String canonical;
        final List<NormalizedName> texts = new ArrayList<>();
        NormalizedName primary;
        String concentration;
        String gender;
        boolean genderInName;
        boolean tester;
        String forma;
        Integer ml;
        final Set<String> gtins = new TreeSet<>();
        final Set<String> skuKeys = new TreeSet<>();
        final Set<String> nameKeys = new LinkedHashSet<>();
        String nameKey;
    }

    static Prepared prepare(NsoBrandDictionary brands, Evidence e) {
        Prepared p = new Prepared();
        List<String> rawTexts = new ArrayList<>();
        if (e.name != null && !e.name.isBlank()) rawTexts.add(e.name);
        for (Offer o : e.offers) {
            if (o.rawTitle != null && !o.rawTitle.isBlank() && !rawTexts.contains(o.rawTitle)) rawTexts.add(o.rawTitle);
        }
        p.brandMatch = brands.resolve(e.brand, rawTexts);
        p.brandResolved = p.brandMatch.isResolved();
        String folded = NsoKeys.brandKey(e.brand);
        if (p.brandResolved) {
            p.canonical = p.brandMatch.brandKey;
        } else if (!folded.isEmpty()) {
            p.canonical = brands.canonicalKey(folded);
        }

        // Palabras de marca a quitar: las de la marca canonica + la columna marca del proveedor.
        Set<String> terms = new LinkedHashSet<>();
        if (p.brandResolved) terms.addAll(brands.brandTerms(p.canonical));
        if (!folded.isEmpty() && !NsoBrandDictionary.VIA_CONTAINS.equals(p.brandMatch.via)) terms.add(folded);
        if (p.brandMatch.matchedPhrase != null) terms.add(p.brandMatch.matchedPhrase);

        Set<String> concs = new TreeSet<>();
        Set<String> genders = new TreeSet<>();
        boolean concAmbigua = false;
        boolean genderAmbigua = false;
        boolean inName = false;
        Set<String> formas = new HashSet<>();
        Set<String> seenCores = new HashSet<>();
        for (String raw : rawTexts) {
            NormalizedName n = NsoNormalizer.normalize(raw, terms);
            if (NsoNormalizer.AMBIGUA.equals(n.concentration)) concAmbigua = true;
            else if (n.concentration != null) concs.add(n.concentration);
            if (NsoNormalizer.AMBIGUA.equals(n.gender)) genderAmbigua = true;
            else if (n.gender != null) genders.add(n.gender);
            if (n.genderInName) inName = true;
            if (n.tester) p.tester = true;
            formas.add(n.forma);
            if (p.ml == null) p.ml = n.ml;
            if (!n.isGeneric() && seenCores.add(String.join(" ", n.tokens))) p.texts.add(n);
        }
        // Un texto cuyas palabras son un subconjunto estricto de otro es una version recortada (Product.name sale
        // de cleanName, que borra "Parfum": "I Want Choo Le Parfum" -> "I Want Choo Le"). Se usa el completo.
        List<NormalizedName> pruned = new ArrayList<>();
        for (NormalizedName n : p.texts) {
            boolean lossy = false;
            for (NormalizedName o : p.texts) {
                if (o == n || o.tokens.size() <= n.tokens.size() || !new HashSet<>(o.tokens).containsAll(n.tokens)) continue;
                // Solo si lo que sobra son palabras: un numero suelto ("+7.5" mal recortado -> "7") no hace
                // mas completo al otro texto.
                boolean extraWords = false;
                for (String tk : o.tokens) {
                    if (!n.tokens.contains(tk) && !tk.chars().allMatch(Character::isDigit)) extraWords = true;
                }
                if (extraWords) {
                    lossy = true;
                    break;
                }
            }
            if (!lossy) pruned.add(n);
        }
        p.texts.clear();
        p.texts.addAll(pruned);
        if (!p.texts.isEmpty()) p.primary = p.texts.get(0);
        // Tipo y categoria solo completan lo que los textos no dicen.
        if (concs.isEmpty() && !concAmbigua && e.type != null) {
            NormalizedName t = NsoNormalizer.normalize(e.type, List.of());
            if (t.concentration != null && !NsoNormalizer.AMBIGUA.equals(t.concentration)) concs.add(t.concentration);
        }
        if (genders.isEmpty() && !genderAmbigua) {
            for (String extra : new String[]{e.type, e.category}) {
                if (extra == null) continue;
                NormalizedName t = NsoNormalizer.normalize(extra, List.of());
                if (t.gender != null && !NsoNormalizer.AMBIGUA.equals(t.gender)) {
                    genders.add(t.gender);
                    break;
                }
            }
        }
        p.concentration = concAmbigua || concs.size() > 1 ? NsoNormalizer.AMBIGUA
                : (concs.isEmpty() ? null : concs.iterator().next());
        p.gender = genderAmbigua || genders.size() > 1 ? NsoNormalizer.AMBIGUA
                : (genders.isEmpty() ? null : genders.iterator().next());
        p.genderInName = inName && p.gender != null && !NsoNormalizer.AMBIGUA.equals(p.gender);

        String productForma = e.forma == null ? null : e.forma.trim().toLowerCase(Locale.ROOT);
        if ("set".equals(productForma)) formas.add(NsoNormalizer.FORMA_SET);
        else if ("deo".equals(productForma) || "body".equals(productForma)) formas.add(NsoNormalizer.FORMA_BODY);
        else if ("oil".equals(productForma)) formas.add(NsoNormalizer.FORMA_OIL);
        if (formas.contains(NsoNormalizer.FORMA_SET)) p.forma = NsoNormalizer.FORMA_SET;
        else if (formas.contains(NsoNormalizer.FORMA_BODY)) p.forma = NsoNormalizer.FORMA_BODY;
        else if (formas.contains(NsoNormalizer.FORMA_OIL)) p.forma = NsoNormalizer.FORMA_OIL;
        else p.forma = NsoNormalizer.FORMA_PERFUME;
        if (e.ml != null) p.ml = e.ml;

        String g = NsoKeys.gtinKey(e.gtin);
        if (g != null) p.gtins.add(g);
        for (Offer o : e.offers) {
            String og = NsoKeys.gtinKey(o.gtin);
            if (og != null) p.gtins.add(og);
            String sk = NsoKeys.skuKey(o.supplierName, o.supplierSku);
            if (sk != null) p.skuKeys.add(sk);
        }
        if (p.canonical != null && !p.canonical.isEmpty()) {
            for (NormalizedName t : p.texts) {
                p.nameKeys.add(NsoKeys.nameKey(p.canonical, t.tokens, p.concentration, p.gender, p.forma));
            }
            if (p.primary != null) p.nameKey = p.nameKeys.iterator().next();
        }
        return p;
    }

    /** NAME_KEY de la evidencia ("marca|core ordenado|concentracion|genero|forma"), o null si no hay nombre. */
    public String nameKey(Evidence e) {
        return prepare(index.brands, e).nameKey;
    }

    /** Todas las NAME_KEY de la evidencia (una por texto distinto: nombre del producto y titulos de ofertas). */
    public List<String> nameKeys(Evidence e) {
        return new ArrayList<>(prepare(index.brands, e).nameKeys);
    }

    // =====================================================================
    // Comparacion nombre del proveedor vs registro
    // =====================================================================

    static final class Comparison {
        final Entry entry;
        final List<String> residualS = new ArrayList<>();
        final List<String> residualR = new ArrayList<>();
        final List<String> typos = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        final List<String> infos = new ArrayList<>();
        double dice;
        double score;
        boolean excluded;
        boolean truncated;

        Comparison(Entry entry) {
            this.entry = entry;
        }

        boolean tokensExact() { return !excluded && residualS.isEmpty() && residualR.isEmpty(); }

        boolean exact() { return tokensExact() && conflicts.isEmpty(); }

        boolean betterThan(Comparison o) {
            if (o == null) return true;
            if (exact() != o.exact()) return exact();
            if (tokensExact() != o.tokensExact()) return tokensExact();
            if (score != o.score) return score > o.score;
            return conflicts.size() < o.conflicts.size();
        }

        List<String> reasons() {
            List<String> out = new ArrayList<>();
            if (tokensExact()) out.add("el nombre coincide con el declarado en la NSO");
            List<String> res = new ArrayList<>(residualS);
            for (String r : residualR) if (!res.contains(r)) res.add(r);
            if (!res.isEmpty()) out.add("palabras que no coinciden: " + res + " (posible otra versión)");
            for (String t : typos) out.add("posible error de escritura: " + t);
            out.addAll(conflicts);
            out.addAll(infos);
            return out;
        }
    }

    Comparison compare(Prepared s, NormalizedName sText, Entry r) {
        Comparison c = new Comparison(r);
        NormalizedName rn = r.norm;
        if (rn.isGeneric()) {
            c.excluded = true;
            return c;
        }
        List<String> st = new ArrayList<>(sText.tokens);
        List<String> rt = new ArrayList<>(rn.tokens);
        joinAdjacent(st, new HashSet<>(rt));
        joinAdjacent(rt, new HashSet<>(st));

        boolean[] usedS = new boolean[st.size()];
        boolean[] usedR = new boolean[rt.size()];
        int m = 0;
        // 1. iguales
        for (int i = 0; i < st.size(); i++) {
            for (int j = 0; j < rt.size(); j++) {
                if (!usedR[j] && st.get(i).equals(rt.get(j))) {
                    usedS[i] = usedR[j] = true;
                    m++;
                    break;
                }
            }
        }
        // 2. typo de 1 letra (ambas >= 5 letras)
        for (int i = 0; i < st.size(); i++) {
            if (usedS[i]) continue;
            String a = st.get(i);
            // Con digitos no hay typo: TORINO21 y TORINO24 son perfumes distintos.
            if (a.length() < 5 || hasDigit(a)) continue;
            for (int j = 0; j < rt.size(); j++) {
                String b = rt.get(j);
                if (usedR[j] || b.length() < 5 || hasDigit(b)) continue;
                if (TYPO_BLOCK.contains(a + "|" + b)) continue;
                // Cambio de letra en palabras de 5 ("AJWAA" vs "AJWAD") es otro nombre; desde 6 letras o con
                // una letra de mas/menos (TUBBES/TUBBEES, HEAVE/HEAVEN) se tolera.
                if (a.length() == b.length() && a.length() < 6) continue;
                if (NsoText.editDistanceIsOne(a, b)) {
                    usedS[i] = usedR[j] = true;
                    m++;
                    c.typos.add(a + " ≈ " + b);
                    break;
                }
            }
        }
        // 3. ultimo token del registro cortado por aduanas (prefijo de una palabra del proveedor)
        boolean truncatable = rn.isTruncatable() && rn.lastTokenIsLastWord() && !rt.isEmpty();
        boolean prefixUsed = false;
        if (truncatable) {
            int last = rt.size() - 1;
            String lt = rt.get(last);
            if (!usedR[last] && lt.length() >= 2) {
                for (int i = 0; i < st.size(); i++) {
                    if (!usedS[i] && st.get(i).length() > lt.length() && st.get(i).startsWith(lt)) {
                        usedS[i] = usedR[last] = true;
                        m++;
                        prefixUsed = true;
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < st.size(); i++) if (!usedS[i]) c.residualS.add(st.get(i));
        for (int j = 0; j < rt.size(); j++) if (!usedR[j]) c.residualR.add(rt.get(j));

        if (m == 0) {
            c.excluded = true;
            return c;
        }
        for (String a : c.residualS) {
            String ant = ANTONYMS.get(a);
            if (ant != null && c.residualR.contains(ant)) {
                c.excluded = true;
                return c;
            }
        }

        c.dice = NsoText.dice(m, m, st.size(), rt.size());
        double jw = NsoText.jaroWinkler(sortedJoin(st), sortedJoin(rt));
        c.score = NsoText.round2(0.75 * c.dice + 0.25 * jw);

        // --- conflictos ---
        String sc = s.concentration, rc = rn.concentration;
        boolean sameTokens = c.residualS.isEmpty() && c.residualR.isEmpty();
        if (NsoNormalizer.AMBIGUA.equals(sc)) {
            c.conflicts.add("concentración ambigua: el proveedor indica más de una");
        } else if (NsoNormalizer.AMBIGUA.equals(rc)) {
            c.conflicts.add("concentración ambigua en la NSO");
        } else if (sc != null && rc != null && !sc.equals(rc)) {
            c.conflicts.add("concentración distinta: " + NsoNormalizer.concentrationLabel(sc) + " vs "
                    + NsoNormalizer.concentrationLabel(rc));
        } else if (NsoNormalizer.PARFUM.equals(sc) && rc == null) {
            // "Phantom Parfum" vs "PHANTOM RE25 150ML": el PARFUM suele ser otra version del perfume base.
            c.conflicts.add("el producto es PARFUM y la NSO no indica concentración (suele ser otra versión)");
        } else if (NsoNormalizer.PARFUM.equals(rc) && sc == null) {
            c.conflicts.add("la NSO es PARFUM y el producto no indica concentración (suele ser otra versión)");
        } else if (sameTokens && rc == null && sc != null
                && hasOtherConcentrationSibling(s.canonical, rn.sortedCompact(), sc, rn.gender, rn.forma)) {
            c.conflicts.add("existen versiones con otra concentración; la NSO no dice cuál");
        } else if (sameTokens && sc == null && rc != null
                && hasOtherConcentrationSibling(s.canonical, rn.sortedCompact(), rc, s.gender, rn.forma)) {
            c.conflicts.add("existen versiones con otra concentración; el producto no dice cuál");
        }

        String sg = s.gender, rg = rn.gender;
        if (NsoNormalizer.AMBIGUA.equals(sg) || NsoNormalizer.AMBIGUA.equals(rg)) {
            c.conflicts.add("género ambiguo: " + NsoNormalizer.genderLabel(sg) + " vs " + NsoNormalizer.genderLabel(rg));
        } else if (sg != null && rg != null && !sg.equals(rg)) {
            c.conflicts.add("género distinto: " + NsoNormalizer.genderLabel(sg) + " vs " + NsoNormalizer.genderLabel(rg));
        } else if (rg != null && sg == null && rn.genderInName) {
            c.conflicts.add("la NSO es la versión «" + rn.genderWord + "» y el producto no indica género");
        } else if (rg == null && sg != null && c.residualS.isEmpty() && c.residualR.isEmpty()) {
            if (hasOtherGenderSibling(s.canonical, rn.sortedCompact(), sg, rc, rn.forma)) {
                c.conflicts.add("existe versión hombre y mujer; la NSO no dice cuál");
            }
        } else if (rg != null && sg == null && c.residualS.isEmpty() && c.residualR.isEmpty()) {
            if (hasOtherGenderSibling(s.canonical, rn.sortedCompact(), rg, rc, rn.forma)) {
                c.conflicts.add("existe versión hombre y mujer; el producto no dice cuál");
            }
        }

        if (NsoNormalizer.FORMA_SET.equals(s.forma) || NsoNormalizer.FORMA_SET.equals(rn.forma)) {
            c.conflicts.add("es un set: confirma que la NSO lo cubra");
        } else if (!Objects.equals(s.forma, rn.forma)) {
            c.conflicts.add("presentación distinta: " + NsoNormalizer.formaLabel(s.forma) + " vs "
                    + NsoNormalizer.formaLabel(rn.forma));
        }

        if (truncatable && (rn.rawLength == NsoNormalizer.TRUNCATED_LENGTH || prefixUsed
                || isStrictPrefixOfAny(rt.get(rt.size() - 1), sText.words))) {
            c.truncated = true;
            c.conflicts.add("nombre de aduanas cortado a 35 letras");
        }
        // Nombre de UNA sola palabra que coincide solo con una letra de diferencia: ahi el typo equivale a cambiar
        // de perfume, asi que nunca es automatico (va a revision con el motivo).
        if (!c.typos.isEmpty() && st.size() == 1 && rt.size() == 1) {
            c.conflicts.add("el nombre es una sola palabra y difiere en una letra (" + c.typos.get(0)
                    + "): confirma que sea el mismo perfume");
        }
        if (s.tester) c.infos.add("es tester");
        return c;
    }

    private boolean hasOtherGenderSibling(String canonical, String sortedCompact, String gender,
                                          String recordConcentration, String recordForma) {
        // Solo hombre vs mujer: un "unisex" en otra lista suele ser el MISMO perfume etiquetado distinto.
        if (!isMenOrWomen(gender)) return false;
        String key = siblingKey(canonical, sortedCompact);
        // Productos (y filas del archivo que se importa) Y registros del catalogo NSO: que la fila quede automatica
        // o en revision no debe depender de que otras filas trajo el Excel del mes.
        return otherGender(index.siblings.get(key), gender, recordConcentration, recordForma)
                || otherGender(index.recordSiblings.get(key), gender, recordConcentration, recordForma);
    }

    private static boolean otherGender(Set<SiblingAttr> attrs, String gender, String recordConcentration,
                                       String recordForma) {
        if (attrs == null) return false;
        for (SiblingAttr a : attrs) {
            if (!isMenOrWomen(a.gender) || a.gender.equals(gender)) continue;
            if (recordConcentration != null && a.concentration != null
                    && !recordConcentration.equals(a.concentration)) continue;
            if (!Objects.equals(recordForma, a.forma)) continue;
            return true;
        }
        return false;
    }

    /** Otro producto con el mismo nombre y OTRA concentracion conocida (genero y forma compatibles). */
    private boolean hasOtherConcentrationSibling(String canonical, String sortedCompact, String concentration,
                                                 String gender, String forma) {
        Set<SiblingAttr> attrs = index.siblings.get(siblingKey(canonical, sortedCompact));
        if (attrs == null) return false;
        for (SiblingAttr a : attrs) {
            if (a.concentration == null || NsoNormalizer.AMBIGUA.equals(a.concentration)
                    || a.concentration.equals(concentration)) continue;
            if (gender != null && !NsoNormalizer.AMBIGUA.equals(gender) && a.gender != null
                    && !gender.equals(a.gender)) continue;
            if (!Objects.equals(forma, a.forma)) continue;
            return true;
        }
        return false;
    }

    private static boolean isMenOrWomen(String gender) {
        return NsoNormalizer.MEN.equals(gender) || NsoNormalizer.WOMEN.equals(gender);
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    private static boolean isStrictPrefixOfAny(String prefix, List<String> words) {
        for (String w : words) if (w.length() > prefix.length() && w.startsWith(prefix)) return true;
        return false;
    }

    /** Une tokens adyacentes cuando la union existe del otro lado ("9 am" -> "9am"). */
    static void joinAdjacent(List<String> tokens, Set<String> other) {
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (i + 2 < tokens.size()) {
                String j3 = tokens.get(i) + tokens.get(i + 1) + tokens.get(i + 2);
                if (other.contains(j3) && !(other.contains(tokens.get(i)) && other.contains(tokens.get(i + 1)))) {
                    tokens.set(i, j3);
                    tokens.remove(i + 2);
                    tokens.remove(i + 1);
                    continue;
                }
            }
            String j2 = tokens.get(i) + tokens.get(i + 1);
            if (other.contains(j2) && !(other.contains(tokens.get(i)) && other.contains(tokens.get(i + 1)))) {
                tokens.set(i, j2);
                tokens.remove(i + 1);
            }
        }
    }

    private static String sortedJoin(List<String> tokens) {
        List<String> c = new ArrayList<>(tokens);
        Collections.sort(c);
        return String.join("", c);
    }

    /** Preferencia entre codigos del mismo nombre: Peru > anio mas nuevo > ultima importacion > codigo. */
    static final Comparator<Entry> PREFERENCE = Comparator
            .comparing((Entry e) -> e.isPeru() ? 0 : 1)
            .thenComparing(e -> -e.year)
            .thenComparing(e -> -e.importKey)
            .thenComparing(e -> e.code);

    // =====================================================================
    // resolve
    // =====================================================================

    /** Filas del preview de import: mismo camino que resolve, con una sola oferta. */
    public Result resolvePreviewRow(String text, String brand, String sku, String gtin, String supplierName) {
        return resolve(previewEvidence(text, brand, sku, gtin, supplierName));
    }

    /** Evidencia de una fila de preview (sirve tambien para Index.Builder.siblings). */
    public static Evidence previewEvidence(String text, String brand, String sku, String gtin, String supplierName) {
        Evidence e = new Evidence();
        e.brand = brand;
        e.gtin = gtin;
        e.offer(supplierName, sku, text, gtin);
        return e;
    }

    public Result resolve(Evidence e) {
        Prepared p = prepare(index.brands, e);
        Result res = new Result();
        res.brandKey = p.canonical;
        res.nameKey = p.nameKey;
        boolean acceptCan = e.acceptCanCodes != null ? e.acceptCanCodes : index.acceptCanCodes;
        fillBrandInfo(p, res);

        // Codigos excluidos: pares rechazados + alias NEGATIVE.
        Set<String> excluded = new TreeSet<>(e.rejectedCodes);
        if (e.productId != null) excluded.addAll(index.rejectedPairs.getOrDefault(e.productId, Set.of()));
        Set<String> negative = new TreeSet<>();
        negative.addAll(index.aliasCodes(index.negativeAliases, NsoAlias.KIND_GTIN, p.gtins));
        negative.addAll(index.aliasCodes(index.negativeAliases, NsoAlias.KIND_SUPPLIER_SKU, p.skuKeys));
        negative.addAll(index.aliasCodes(index.negativeAliases, NsoAlias.KIND_NAME_KEY, p.nameKeys));
        boolean rejectAllByName = negative.remove(NsoAlias.NO_CODE);
        excluded.addAll(negative);

        // 0. decision bloqueada
        if (e.locked) {
            if (e.lockedCode != null && !e.lockedCode.isBlank()) {
                if (index.isActiveCode(e.lockedCode)) {
                    res.status = e.lockedStatus != null ? e.lockedStatus : ProductNso.STATUS_CON_NSO;
                    res.nsoCode = e.lockedCode;
                    res.matchedBy = e.lockedMatchedBy != null ? e.lockedMatchedBy : ProductNso.MATCHED_MANUAL;
                    res.addReason("decisión tuya: se mantiene");
                    return res;
                }
                res.addReason("el código " + e.lockedCode + " que elegiste ya no está activo: se volvió a verificar");
            } else if (e.lockedStatus != null) {
                res.status = e.lockedStatus;
                res.addReason("decisión tuya: se mantiene");
                return res;
            }
        }

        // 1. GTIN
        Set<String> gtinCodes = new TreeSet<>();
        for (String g : p.gtins) {
            Set<String> c = index.eanToCodes.get(g);
            if (c != null) gtinCodes.addAll(c);
        }
        gtinCodes.addAll(index.aliasCodes(index.positiveAliases, NsoAlias.KIND_GTIN, p.gtins));
        gtinCodes = usable(gtinCodes, excluded, acceptCan, res);
        if (!gtinCodes.isEmpty()) {
            List<String> sameBrand = new ArrayList<>();
            for (String code : gtinCodes) {
                Entry en = index.entriesByCode.get(code);
                if (p.brandResolved && en.canonicalBrand.equals(p.canonical)) sameBrand.add(code);
            }
            if (sameBrand.size() == 1 && gtinCodes.size() == 1) {
                setConNso(res, sameBrand.get(0), ProductNso.MATCHED_UPC, 1.0);
                res.addReason("el código de barras coincide con el registrado en la NSO");
            } else {
                res.status = ProductNso.STATUS_EN_REVISION;
                if (sameBrand.isEmpty()) {
                    res.addReason("el código de barras coincide con una NSO de otra marca: revisa");
                } else {
                    res.addReason("el código de barras coincide con varias NSO: revisa cuál corresponde");
                }
                for (String code : gtinCodes) {
                    Entry en = index.entriesByCode.get(code);
                    List<String> rs = new ArrayList<>();
                    rs.add("mismo código de barras");
                    if (!sameBrand.contains(code)) rs.add("marca distinta: " + en.record.getBrand());
                    addCandidate(res, code, 1.0, rs, NsoCandidate.ORIGIN_MATCHER, false);
                }
            }
            applyResearch(p, e, res, excluded, acceptCan);
            return res;
        }

        // 2. alias aprobados
        Set<String> skuCodes = usable(index.aliasCodes(index.positiveAliases, NsoAlias.KIND_SUPPLIER_SKU, p.skuKeys),
                excluded, acceptCan, res);
        Set<String> nameCodes = usable(index.aliasCodes(index.positiveAliases, NsoAlias.KIND_NAME_KEY, p.nameKeys),
                excluded, acceptCan, res);
        Set<String> aliasCodes = new TreeSet<>(skuCodes);
        aliasCodes.addAll(nameCodes);
        if (aliasCodes.size() == 1) {
            String code = aliasCodes.iterator().next();
            if (!skuCodes.isEmpty()) {
                setConNso(res, code, ProductNso.MATCHED_ALIAS_SKU, 1.0);
                res.addReason("ya lo aprobaste antes (mismo SKU del proveedor)");
            } else {
                setConNso(res, code, ProductNso.MATCHED_ALIAS_NOMBRE, 1.0);
                res.addReason("ya lo aprobaste antes (mismo perfume en otro tamaño o proveedor)");
            }
            applyResearch(p, e, res, excluded, acceptCan);
            return res;
        }
        if (aliasCodes.size() > 1) {
            res.status = ProductNso.STATUS_EN_REVISION;
            res.addReason("decisiones anteriores en conflicto: revisa cuál corresponde");
            for (String code : aliasCodes) {
                addCandidate(res, code, 1.0, List.of("aprobado antes para este perfume"), NsoCandidate.ORIGIN_MATCHER, false);
            }
            applyResearch(p, e, res, excluded, acceptCan);
            return res;
        }

        // 3. nombre
        if (!p.brandResolved || !index.brands.hasCatalogRecords(p.canonical)) {
            res.status = ProductNso.STATUS_SIN_NSO;
            String shown = e.brand == null || e.brand.isBlank() ? "del producto" : "«" + e.brand.trim() + "»";
            res.addReason("la marca " + shown + " no tiene ningún NSO en tu lista");
            if (p.brandMatch.suggestedBrandKey != null) {
                res.suggestedBrandKey = p.brandMatch.suggestedBrandKey;
                res.suggestedBrand = p.brandMatch.suggestedBrand;
                res.addReason("¿es la misma marca que " + p.brandMatch.suggestedBrand + "?");
            }
            applyResearch(p, e, res, excluded, acceptCan);
            return res;
        }

        Map<String, Comparison> best = new TreeMap<>();
        Set<String> otherCountry = new TreeSet<>();
        if (!rejectAllByName) {
            for (Entry r : index.entriesByBrand.getOrDefault(p.canonical, List.of())) {
                if (excluded.contains(r.code)) continue;
                Comparison bestForCode = null;
                for (NormalizedName t : p.texts) {
                    Comparison c = compare(p, t, r);
                    if (c.excluded) continue;
                    if (c.betterThan(bestForCode)) bestForCode = c;
                }
                if (bestForCode == null || !isCandidate(bestForCode)) continue;
                if (!acceptCan && !r.isPeru()) {
                    otherCountry.add(r.code);
                    continue;
                }
                best.put(r.code, bestForCode);
            }
        } else {
            res.addReason("ya descartaste las NSO de este nombre antes");
        }
        if (!otherCountry.isEmpty()) {
            res.addReason("hay NSO de otro país que tu configuración no acepta: " + String.join(", ", otherCountry));
        }

        List<Comparison> exact = new ArrayList<>();
        for (Comparison c : best.values()) if (c.exact()) exact.add(c);
        if (p.gender != null && !NsoNormalizer.AMBIGUA.equals(p.gender)) {
            List<Comparison> explicit = new ArrayList<>();
            for (Comparison c : exact) if (p.gender.equals(c.entry.norm.gender)) explicit.add(c);
            if (!explicit.isEmpty()) exact = explicit;
        }
        if (!exact.isEmpty()) {
            exact.sort((a, b) -> PREFERENCE.compare(a.entry, b.entry));
            if (sameName(exact)) {
                // Igual que con el genero: si el proveedor dice la concentracion, gana la NSO que declara ESA
                // concentracion sobre una que no dice nada (aunque esta sea mas nueva: puede ser otra version).
                List<Comparison> pool = exact;
                if (p.concentration != null && !NsoNormalizer.AMBIGUA.equals(p.concentration)) {
                    List<Comparison> sameConc = new ArrayList<>();
                    for (Comparison c : exact) if (p.concentration.equals(c.entry.norm.concentration)) sameConc.add(c);
                    if (!sameConc.isEmpty()) pool = sameConc;
                }
                boolean narrowed = pool.size() < exact.size();
                Comparison chosen = pool.get(0);
                setConNso(res, chosen.entry.code, ProductNso.MATCHED_NOMBRE, chosen.score);
                res.addReason("el nombre coincide con el declarado en la NSO: «" + chosen.entry.record.getDeclaredName() + "»");
                if (p.concentration != null && chosen.entry.norm.concentration != null) {
                    res.addReason("misma concentración (" + NsoNormalizer.concentrationLabel(p.concentration) + ")");
                }
                if (p.gender != null && chosen.entry.norm.gender != null) {
                    res.addReason("mismo género (" + NsoNormalizer.genderLabel(p.gender) + ")");
                }
                if (exact.size() > 1) {
                    // No se afirma que sean renovaciones: pueden ser inscripciones distintas con el mismo nombre.
                    String how = !narrowed ? "la más reciente"
                            : pool.size() > 1 ? "la más reciente de las que declaran esa misma concentración"
                            : "la que declara esa misma concentración";
                    res.addReason("hay " + exact.size() + " NSO con este mismo nombre: se eligió " + how);
                    for (Comparison c : exact) if (c != chosen) res.renewalCodes.add(c.entry.code);
                }
                if (!chosen.entry.isPeru()) res.addReason("NSO de otro país (" + chosen.entry.country + ")");
                // Palabras aceptadas con una letra de diferencia: que quede a la vista en lo que la duena aprueba.
                for (String t : chosen.typos) res.addReason("se aceptó una diferencia de una letra: " + t);
                for (String info : chosen.infos) res.addReason(info);
                applyResearch(p, e, res, excluded, acceptCan);
                return res;
            }
            res.status = ProductNso.STATUS_EN_REVISION;
            res.addReason("varias NSO distintas coinciden con este nombre: revisa cuál corresponde");
            for (Comparison c : exact) {
                if (res.candidates.size() >= MAX_CANDIDATES) break;
                addCandidate(res, c.entry.code, c.score, c.reasons(), NsoCandidate.ORIGIN_MATCHER, true);
            }
            applyResearch(p, e, res, excluded, acceptCan);
            return res;
        }

        // 4. candidatos a revision
        List<Comparison> cands = new ArrayList<>(best.values());
        // Orden: puntaje, luego menos conflictos (el que solo difiere en algo menor va primero), luego preferencia.
        cands.sort((a, b) -> {
            if (a.score != b.score) return Double.compare(b.score, a.score);
            if (a.conflicts.size() != b.conflicts.size()) return Integer.compare(a.conflicts.size(), b.conflicts.size());
            return PREFERENCE.compare(a.entry, b.entry);
        });
        if (!cands.isEmpty()) {
            res.status = ProductNso.STATUS_EN_REVISION;
            Comparison top = cands.get(0);
            res.addReason(top.tokensExact()
                    ? "el nombre coincide pero hay diferencias: confirma"
                    : "no hay una coincidencia exacta: revisa las opciones");
            for (String r : top.conflicts) res.addReason(r);
            for (Comparison c : cands) {
                if (res.candidates.size() >= MAX_CANDIDATES) break;
                addCandidate(res, c.entry.code, c.score, c.reasons(), NsoCandidate.ORIGIN_MATCHER, c.tokensExact());
            }
            res.score = top.score;
            applyResearch(p, e, res, excluded, acceptCan);
            return res;
        }

        // 5. marca con NSO
        res.status = ProductNso.STATUS_MARCA_CON_NSO;
        res.addReason("la marca " + index.brands.displayName(p.canonical)
                + " tiene NSO, pero no encontramos este perfume en tu lista");
        applyResearch(p, e, res, excluded, acceptCan);
        return res;
    }

    private boolean isCandidate(Comparison c) {
        if (c.excluded) return false;
        if (c.tokensExact()) return true;
        return c.dice >= index.reviewMinScore && c.residualS.size() <= 1 && c.residualR.size() <= 1;
    }

    /** Todos los exactos son el mismo perfume: mismas palabras y a lo sumo una concentracion/genero/forma conocidos. */
    private static boolean sameName(List<Comparison> exact) {
        Set<String> cores = new HashSet<>();
        Set<String> concs = new HashSet<>();
        Set<String> genders = new HashSet<>();
        Set<String> formas = new HashSet<>();
        for (Comparison c : exact) {
            NormalizedName n = c.entry.norm;
            cores.add(n.sortedCompact());
            if (n.concentration != null) concs.add(n.concentration);
            if (n.gender != null) genders.add(n.gender);
            formas.add(n.forma);
        }
        return cores.size() == 1 && concs.size() <= 1 && genders.size() <= 1 && formas.size() == 1;
    }

    /** Filtra codigos inactivos, excluidos y (si la config lo pide) de otro pais. */
    private Set<String> usable(Set<String> codes, Set<String> excluded, boolean acceptCan, Result res) {
        Set<String> out = new TreeSet<>();
        for (String code : codes) {
            Entry en = index.entriesByCode.get(code);
            if (en == null || excluded.contains(code)) continue;
            if (!acceptCan && !en.isPeru()) {
                res.addReason("hay NSO de otro país que tu configuración no acepta: " + code);
                continue;
            }
            out.add(code);
        }
        return out;
    }

    private void setConNso(Result res, String code, String matchedBy, double score) {
        res.status = ProductNso.STATUS_CON_NSO;
        res.nsoCode = code;
        res.matchedBy = matchedBy;
        res.score = NsoText.round2(score);
    }

    private static void addCandidate(Result res, String code, double score, List<String> reasons, String origin,
                                     boolean exactName) {
        for (Candidate c : res.candidates) if (c.code.equals(code)) return;
        res.candidates.add(new Candidate(code, NsoText.round2(score), reasons, origin, exactName));
    }

    private void fillBrandInfo(Prepared p, Result res) {
        if (!p.brandResolved || !index.brands.hasCatalogRecords(p.canonical)) return;
        Map<String, Titular> byTitular = new TreeMap<>();
        Map<String, List<String>> codes = new TreeMap<>();
        Map<String, String[]> names = new HashMap<>();
        int generic = 0;
        int unknown = 0;
        for (Entry en : index.entriesByBrand.getOrDefault(p.canonical, List.of())) {
            if (en.norm.isGeneric()) generic++;
            String titular = en.record.getTitular();
            String ruc = en.record.getRuc();
            if (titular == null || titular.isBlank()) {
                unknown++;
                titular = "titular desconocido (Aduanet)";
                ruc = null;
            }
            String key = titular.trim() + "|" + (ruc == null ? "" : ruc.trim());
            codes.computeIfAbsent(key, k -> new ArrayList<>()).add(en.code);
            names.putIfAbsent(key, new String[]{titular.trim(), ruc == null ? null : ruc.trim()});
        }
        for (Map.Entry<String, List<String>> e : codes.entrySet()) {
            String[] n = names.get(e.getKey());
            byTitular.put(e.getKey(), new Titular(n[0], n[1], e.getValue()));
        }
        res.brandTitulares.addAll(byTitular.values());
        res.genericRecords = generic;
        res.unknownTitularCodes = unknown;
    }

    // =====================================================================
    // 7. investigacion anterior (solo evidencia)
    // =====================================================================

    private void applyResearch(Prepared p, Evidence e, Result res, Set<String> excluded, boolean acceptCan) {
        Map<String, String> hints = new TreeMap<>();
        e.researchCodes.forEach((code, detail) -> {
            if (code != null) hints.putIfAbsent(code, detail);
        });
        for (String k : p.skuKeys) addHints(hints, index.researchAliases.get(aliasMapKey(NsoAlias.KIND_SUPPLIER_SKU, k)));
        for (String k : p.gtins) addHints(hints, index.researchAliases.get(aliasMapKey(NsoAlias.KIND_GTIN, k)));
        if (hints.isEmpty()) return;

        List<String> suggested = new ArrayList<>();
        for (Map.Entry<String, String> h : hints.entrySet()) {
            String code = h.getKey();
            Entry en = index.entriesByCode.get(code);
            if (en == null || excluded.contains(code) || (!acceptCan && !en.isPeru())) continue;
            if (code.equals(res.nsoCode)) {
                res.addReason("coincide con tu investigación");
                continue;
            }
            if (res.renewalCodes.contains(code)) {
                // Mismo nombre declarado, otra renovacion: no contradice lo reconocido.
                res.addReason("tu investigación sugería " + code + ", otra NSO con este mismo nombre");
                continue;
            }
            boolean inCandidates = false;
            for (Candidate c : res.candidates) {
                if (c.code.equals(code)) {
                    inCandidates = true;
                    break;
                }
            }
            if (inCandidates) {
                res.addReason("tu investigación anterior también sugería " + code);
                continue;
            }
            suggested.add(code);
        }
        if (suggested.isEmpty()) return;

        if (res.isConNso()) {
            if (!ProductNso.MATCHED_NOMBRE.equals(res.matchedBy)) {
                res.addReason("tu investigación anterior sugería " + String.join(", ", suggested)
                        + ": se mantiene lo reconocido");
                return;
            }
            // Reconocido por nombre pero la investigacion dice otro codigo: a revision.
            String code = res.nsoCode;
            Double score = res.score;
            List<String> rs = new ArrayList<>(res.reasons);
            rs.add("el nombre coincide, pero tu investigación sugería otro código");
            res.reasons.clear();
            res.status = ProductNso.STATUS_EN_REVISION;
            res.nsoCode = null;
            res.matchedBy = null;
            res.renewalCodes.clear();
            res.addReason("el nombre coincide con una NSO, pero tu investigación sugería otro código: confirma");
            addCandidate(res, code, score == null ? 0 : score, rs, NsoCandidate.ORIGIN_MATCHER, true);
        } else {
            res.status = ProductNso.STATUS_EN_REVISION;
            res.addReason("tu investigación anterior sugería un código: confírmalo");
        }
        for (String code : suggested) {
            String detail = hints.get(code);
            String level = researchLevel(detail);
            List<String> rs = new ArrayList<>();
            rs.add("tu investigación anterior sugería " + code + (level == null ? "" : " (" + level + ")"));
            double score = 0;
            Entry en = index.entriesByCode.get(code);
            if (p.brandResolved && en.canonicalBrand.equals(p.canonical)) {
                Comparison bestC = null;
                for (NormalizedName t : p.texts) {
                    Comparison c = compare(p, t, en);
                    if (!c.excluded && c.betterThan(bestC)) bestC = c;
                }
                if (bestC != null) {
                    score = bestC.score;
                    rs.addAll(bestC.reasons());
                } else {
                    rs.add("el nombre no se parece al declarado en la NSO");
                }
            } else {
                rs.add("la NSO es de otra marca: " + en.record.getBrand());
            }
            addCandidate(res, code, score, rs, NsoCandidate.ORIGIN_RESEARCH, false);
        }
    }

    private static void addHints(Map<String, String> hints, List<ResearchHint> list) {
        if (list == null) return;
        for (ResearchHint h : list) hints.putIfAbsent(h.code, h.detail);
    }

    /** "ALTA · Afnan Supremacy..." -> "ALTA". */
    static String researchLevel(String detail) {
        if (detail == null || detail.isBlank()) return null;
        String d = detail.trim();
        int i = d.indexOf('·');
        String level = (i >= 0 ? d.substring(0, i) : d).trim().toUpperCase(Locale.ROOT);
        return level.equals("ALTA") || level.equals("MEDIA") || level.equals("BAJA") ? level : null;
    }
}
