package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.service.matching.FingerprintExtractor;
import org.example.backendbvaberiaperfumes.service.matching.MatchScorer;
import org.example.backendbvaberiaperfumes.service.matching.MatchScorer.Decision;
import org.example.backendbvaberiaperfumes.service.matching.ProductFingerprint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pares REALES de los Excel de FragranceSense (mayusculas, ML) y Zimaxx
 * (Mixed Case, Oz). El objetivo del scorer: mismo perfume -> AUTO;
 * flanker/tester/genero distinto -> jamas AUTO.
 */
class MatchScorerTest {

    private static ParsedRow row(String brand, String rawTitle, Integer ml, String forma) {
        ParsedRow r = new ParsedRow();
        r.brand = brand;
        r.rawTitle = rawTitle;
        r.name = org.example.backendbvaberiaperfumes.util.PerfumeNormalizer.cleanName(
                org.example.backendbvaberiaperfumes.util.PerfumeNormalizer.stripBrandPrefix(brand, rawTitle));
        r.ml = ml;
        r.forma = forma != null ? forma
                : org.example.backendbvaberiaperfumes.util.PerfumeNormalizer.detectForma(rawTitle);
        return r;
    }

    private static MatchScorer.Result score(ParsedRow a, ParsedRow b) {
        ProductFingerprint fa = FingerprintExtractor.fromRow(a);
        ProductFingerprint fb = FingerprintExtractor.fromRow(b);
        return MatchScorer.score(fa, fb, MatchScorer.DEFAULT_REVIEW_JACCARD);
    }

    // ================= CASOS QUE DEBEN MATCHEAR =================

    @Test
    void khamrahFsVsZimaxxEsAutoMatch() {
        // FS: "LATTAFA KHAMRAH UNISEX 100ML EDP SPRAY" / ZX: "Khamrah 3.4 Oz Edp Unisex"
        ParsedRow fs = row("LATTAFA", "LATTAFA KHAMRAH UNISEX 100ML EDP SPRAY", 100, null);
        ParsedRow zx = row("Lattafa", "Khamrah 3.4 Oz Edp Unisex", 100, null);
        MatchScorer.Result r = score(fs, zx);
        assertEquals(Decision.AUTO_MATCH, r.decision, "mismo perfume, atributos completos e iguales: " + r.reasons);
    }

    @Test
    void khamrahDukhanEntreProveedoresEsAutoMatch() {
        ParsedRow fs = row("LATTAFA", "LATTAFA KHAMRAH DUKHAN 100ML EDP SPRAY UNISEX", 100, null);
        ParsedRow zx = row("Lattafa", "Khamrah Dukhan 3.4 Oz Edp Unisex", 100, null);
        assertEquals(Decision.AUTO_MATCH, score(fs, zx).decision);
    }

    // ================= TRAMPA DE FLANKERS: JAMAS AUTO =================

    @Test
    void yaraVsYaraCandyNuncaAuto() {
        ParsedRow yara = row("Lattafa", "Yara 3.4 Oz Edp Women", 100, null);
        ParsedRow candy = row("Lattafa", "Yara Candy 3.4 Oz Edp Women", 100, null);
        MatchScorer.Result r = score(yara, candy);
        assertNotEquals(Decision.AUTO_MATCH, r.decision, "flanker: " + r.reasons);
    }

    @Test
    void khamrahVsKhamrahDukhanNuncaAuto() {
        ParsedRow a = row("Lattafa", "Khamrah 3.4 Oz Edp Unisex", 100, null);
        ParsedRow b = row("Lattafa", "Khamrah Dukhan 3.4 Oz Edp Unisex", 100, null);
        assertNotEquals(Decision.AUTO_MATCH, score(a, b).decision);
    }

    @Test
    void asadVsAsadZanzibarNuncaAuto() {
        ParsedRow a = row("Lattafa", "Asad 3.4 Oz Edp Men", 100, null);
        ParsedRow b = row("Lattafa", "Asad Zanzibar 3.4 Oz Men Edp", 100, null);
        assertNotEquals(Decision.AUTO_MATCH, score(a, b).decision);
    }

    @Test
    void eclaireVsEclairePistacheNuncaAuto() {
        ParsedRow a = row("Lattafa", "Eclaire 3.4 Oz Edp Unisex", 100, null);
        ParsedRow b = row("Lattafa", "Eclaire Pistache 3.4 Oz Edp Unisex", 100, null);
        assertNotEquals(Decision.AUTO_MATCH, score(a, b).decision);
    }

    // ================= TESTER / GENERO / CONCENTRACION / TAMANO =================

    @Test
    void testerVsRegularEsNew() {
        // Versace Eros Parfum: tester (...2084) y regular (...2077) son UPC legitimos distintos.
        ParsedRow tester = row("VERSACE", "VERSACE EROS MEN 100ML PARFUM SPRAY TESTER", 100, null);
        ParsedRow regular = row("Versace", "Eros 3.4 Oz Parfum Men", 100, null);
        MatchScorer.Result r = score(tester, regular);
        assertEquals(Decision.NEW, r.decision, "tester nunca se cruza con regular: " + r.reasons);
    }

    @Test
    void devotionMenVsWomenEsNew() {
        // Caso real: mismo j=1.0 con el limpiador viejo porque men/women eran stopwords.
        ParsedRow men = row("DOLCE & GABBANA", "DOLCE & GABBANA DEVOTION MEN 100ML EDP SPRAY", 100, null);
        ParsedRow women = row("Dolce & Gabbana", "Devotion 3.4 Oz Edp Women", 100, null);
        assertEquals(Decision.NEW, score(men, women).decision);
    }

    @Test
    void edpVsParfumEsNew() {
        ParsedRow edp = row("Versace", "Eros 3.4 Oz Edp Men", 100, null);
        ParsedRow parfum = row("Versace", "Eros 3.4 Oz Parfum Men", 100, null);
        assertEquals(Decision.NEW, score(edp, parfum).decision);
    }

    @Test
    void tamanosDistintosEsNew() {
        ParsedRow ml50 = row("Lattafa", "Yara 1.7 Oz Edp Women", 50, null);
        ParsedRow ml100 = row("Lattafa", "Yara 3.4 Oz Edp Women", 100, null);
        assertEquals(Decision.NEW, score(ml50, ml100).decision);
    }

    @Test
    void ozRedondeadoVsMlExactoSiMatchea() {
        // 3.4 Oz -> 100.55ml se guarda como 100; 3.3 Oz -> 97.6 -> 100 (multiplos de 5).
        ParsedRow oz = row("Lattafa", "Khamrah 3.4 Oz Edp Unisex", 101, null);
        ParsedRow ml = row("LATTAFA", "LATTAFA KHAMRAH UNISEX 100ML EDP SPRAY", 100, null);
        assertEquals(Decision.AUTO_MATCH, score(oz, ml).decision);
    }

    // ================= DATOS INCOMPLETOS: TOPE REVIEW =================

    @Test
    void mlDesconocidoEnUnLadoComoMaximoReview() {
        ParsedRow sinMl = row("Lattafa", "Khamrah Edp Unisex", null, null);
        ParsedRow conMl = row("Lattafa", "Khamrah 3.4 Oz Edp Unisex", 100, null);
        MatchScorer.Result r = score(sinMl, conMl);
        assertEquals(Decision.REVIEW, r.decision, String.valueOf(r.reasons));
    }

    @Test
    void generoDesconocidoEnUnLadoComoMaximoReview() {
        // FS sin genero en el titulo vs ZX Unisex: nombres identicos -> REVIEW (no AUTO).
        ParsedRow sinGenero = row("LATTAFA", "LATTAFA SER AL KHULOOD BROWN 100ML EDP SPRAY", 100, null);
        ParsedRow conGenero = row("Lattafa", "Ser Al Khulood Brown 3.4 Oz Edp Unisex", 100, null);
        MatchScorer.Result r = score(sinGenero, conGenero);
        assertEquals(Decision.REVIEW, r.decision, String.valueOf(r.reasons));
    }

    // ================= MARCAS =================

    @Test
    void marcaParcialCompatible() {
        // El parser generico de FS toma solo la primera palabra como marca.
        ParsedRow fs = row("CAROLINA", "CAROLINA HERRERA GOOD GIRL BLUSH WOMEN 80ML EDP SPRAY", 80, null);
        ParsedRow zx = row("Carolina Herrera", "Good Girl Blush 2.7 Oz Edp Women", 80, null);
        MatchScorer.Result r = score(fs, zx);
        assertNotEquals(Decision.NEW, r.decision, "marca contenida debe ser compatible: " + r.reasons);
    }

    @Test
    void marcasDistintasEsNew() {
        ParsedRow a = row("Lattafa", "Khamrah 3.4 Oz Edp Unisex", 100, null);
        ParsedRow b = row("Armaf", "Khamrah 3.4 Oz Edp Unisex", 100, null);
        assertEquals(Decision.NEW, score(a, b).decision);
    }

    // ================= NUMEROS QUE SON NOMBRE =================

    @Test
    void numeros212SeConservanEnElNombre() {
        ProductFingerprint fp = FingerprintExtractor.fromRow(
                row("Carolina Herrera", "212 Vip Black 3.4 Oz Edp Men", 100, null));
        assertTrue(fp.coreTokens.contains("212"), "212 es nombre, no tamano: " + fp.coreTokens);
        assertFalse(fp.coreTokens.contains("3.4"), "3.4 es tamano: " + fp.coreTokens);
    }

    @Test
    void nombre9pmSeConserva() {
        ProductFingerprint fp = FingerprintExtractor.fromRow(
                row("Afnan", "9pm 3.4 Oz Edp Men", 100, null));
        assertTrue(fp.coreTokens.contains("9pm"), String.valueOf(fp.coreTokens));
    }

    // ================= SETS =================

    @Test
    void setVsSingleEsNew() {
        ParsedRow single = row("Lattafa", "Khamrah 3.4 Oz Edp Unisex", 100, null);
        ParsedRow set = row("Lattafa", "Set Khamrah 3 Pcs 3.4 Oz Edp + 12Ml Travel Spray", 100, "set");
        assertEquals(Decision.NEW, score(single, set).decision);
    }
}
