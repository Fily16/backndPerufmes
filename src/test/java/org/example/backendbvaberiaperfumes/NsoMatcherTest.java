package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.example.backendbvaberiaperfumes.model.NsoCandidate;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.example.backendbvaberiaperfumes.service.nso.NsoBrandDictionary;
import org.example.backendbvaberiaperfumes.service.nso.NsoCode;
import org.example.backendbvaberiaperfumes.service.nso.NsoKeys;
import org.example.backendbvaberiaperfumes.service.nso.NsoMatcher;
import org.example.backendbvaberiaperfumes.service.nso.NsoMatcher.Candidate;
import org.example.backendbvaberiaperfumes.service.nso.NsoMatcher.Evidence;
import org.example.backendbvaberiaperfumes.service.nso.NsoMatcher.Result;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reconocimiento NSO con registros REALES del catalogo (verificados en NSO-perfumes-FINAL.xlsx, hoja
 * "Catalogo NSO Peru") y textos REALES de Zimaxx, Oasis y FragranceSense. Fixture inline: no lee archivos.
 */
class NsoMatcherTest {

    private static final String PUIG = "TITULAR DEMO UNO S.A";
    private static final String PUIG_RUC = "20999999901";
    private static final String MORYA = "TITULAR DEMO DOS S.A.C";
    private static final String MORYA_RUC = "20999999902";
    private static final String BEAUTY = "TITULAR DEMO TRES S.A.C";
    private static final String BEAUTY_RUC = "20999999903";
    private static final String PU = "TITULAR DEMO CUATRO S.A";
    private static final String PU_RUC = "20999999904";

    // ---- Afnan
    static final NsoRecord SUPREMACY_SILVER = rec("NSOC77051-25PE", "AFNAN", "AGUA DE PERFUME SUPREMACY SILVER", MORYA, MORYA_RUC);
    static final NsoRecord SUPREMACY_GALA = rec("NSOC78874-26PE", "AFNAN", "EXTRACTO DE PERFUME-SUPREMACY GALA", MORYA, MORYA_RUC);
    static final NsoRecord NUEVE_AM_FEMME = rec("NSOC81196-26PE", "AFNAN", "AGUA DE PERFUME - 9AM POUR FEMME", MORYA, MORYA_RUC);
    // ---- Antonio Banderas
    static final NsoRecord KING = rec("NSOC49004-21PE", "ANTONIO BANDERAS", "KING OF SEDUCTION 100ML EDT", PUIG, PUIG_RUC);
    static final NsoRecord ICON_EDP = rec("NSOC52223-22PE", "ANTONIO BANDERAS", "AB THE ICON EDP 100ML", PUIG, PUIG_RUC);
    static final NsoRecord ICON_EDT = rec("NSOC44933-20PE", "ANTONIO BANDERAS", "AB THE ICON EDT 200ML", PUIG, PUIG_RUC);
    static final NsoRecord ICON_ELIXIR = rec("NSOC55930-22PE", "ANTONIO BANDERAS", "AB THE ICON ELIXIR EDP 100ML", PUIG, PUIG_RUC);
    static final NsoRecord AB_BLUE = rec("NSOC34322-07PE", "ANTONIO BANDERAS", "AB BLUE EDT 100ML VAP C/20UD", PUIG, PUIG_RUC);
    static final NsoRecord AB_BLACK = rec("NSOC26592-16PE", "ANTONIO BANDERAS", "AB BLACK EDT 100ML", PUIG, PUIG_RUC);
    static final NsoRecord ICON_FEM = rec("NSOC55872-22PE", "ANTONIO BANDERAS", "AB THE ICON FEM EDP 100ML TT",
            "TITULAR DEMO CINCO S.A.C", "20999999905");
    // ---- Armaf
    static final NsoRecord CDN_WOMAN = rec("NSOC72246-25PE", "ARMAF", "AGUA DE PERFUME-CLUB DE NUIT WOMAN", MORYA, MORYA_RUC);
    static final NsoRecord CDN_OUD = rec("NSOC76211-25PE", "ARMAF", "PERFUME- CLUB DE NUIT OUD 105ML", MORYA, MORYA_RUC);
    static final NsoRecord CDN_INTEN_W = rec("NSOC69229-24PE", "ARMAF", "AGUA DE PERFUME-CLUB D NUIT INTEN W", MORYA, MORYA_RUC);
    static final NsoRecord CDN_INT200 = rec("NSOC69494-24PE", "ARMAF", "AGUA DE PERFUME-CLUB DE NUIT INT200", MORYA, MORYA_RUC);
    static final NsoRecord CDN_INT_MA = rec("NSOC70987-25PE", "ARMAF", "AGUA DE PERFUME-CLUB DE NUIT INT MA", MORYA, MORYA_RUC);
    static final NsoRecord CDN_MAN_EDT = rec("NSOC80741-26PE", "ARMAF", "ARMAF CLUB DE NUIT MAN EAU DE TOILETTE", BEAUTY, BEAUTY_RUC);
    static final NsoRecord WILD_ONE = rec("NSOC74282-25PE", "ARMAF", "AGUA DE PERFUME-ODYSSEY WILD ONE100", MORYA, MORYA_RUC);
    static final NsoRecord MANDARIN_SK = rec("NSOC72991-25PE", "ARMAF", "AGUA DE PERFUME-ODYSSEY MANDARIN SK", MORYA, MORYA_RUC);
    // ---- Lattafa
    static final NsoRecord YARA_CANDY_MORYA = rec("NSOC74343-25PE", "LATTAFA", "EAU DE PARFUM-YARA CANDY", MORYA, MORYA_RUC);
    static final NsoRecord YARA_CANDY_BEAUTY = rec("NSOC76672-25PE", "LATTAFA", "SPRAY YARA CANDY 100 ML", BEAUTY, BEAUTY_RUC);
    static final NsoRecord YARA_ELIXIR = rec("NSOC71005-25PE", "LATTAFA", "SPRAY YARA ELIXIR 100 ML", BEAUTY, BEAUTY_RUC);
    static final NsoRecord YARA_MOI = rec("NSOC71217-25PE", "LATTAFA", "YARA EAU DE PERFUME MOI", BEAUTY, BEAUTY_RUC);
    static final NsoRecord TERIAQ = rec("NSOC70715-25PE", "LATTAFA", "TERIAQ EAU DE PARFUM", BEAUTY, BEAUTY_RUC);
    static final NsoRecord AJWAD = rec("NSOC72962-25PE", "LATTAFA", "SPRAY AJWAD 60ML", BEAUTY, BEAUTY_RUC);
    // ---- Jean Paul Gaultier
    static final NsoRecord SCANDAL_EDP = rec("NSOC30896-17PE", "JEAN PAUL GAULTIER", "JPG SCANDAL EDP SPRAY 50ML", PUIG, PUIG_RUC);
    static final NsoRecord LM_EDP = rec("NSOC45801-20PE", "JEAN PAUL GAULTIER", "JPG LM EDP 75ML", PUIG, PUIG_RUC);
    // ---- Paco Rabanne / Rabanne (dos marcas del catalogo, un solo grupo)
    static final NsoRecord PHANTOM_EDT = rec("NSOC48747-21PE", "PACO RABANNE", "PHANTOM EDT 50ML", PUIG, PUIG_RUC);
    static final NsoRecord PHANTOM_PARFUM = rec("NSOC58456-23PE", "PACO RABANNE", "PHANTOM PARFUM 100ML", PUIG, PUIG_RUC);
    static final NsoRecord PHANTOM_RE25 = rec("NSOC71223-25PE", "RABANNE", "PHANTOM RE25 150ML REFILLABLE", PUIG, PUIG_RUC);
    // ---- otros
    static final NsoRecord BRIGHT_CRYSTAL_EDT = rec("NSOC62198-23PE", "VERSACE", "BRIGHT CRYSTAL EAU DE TOILETTE", PU, PU_RUC);
    static final NsoRecord I_WANT_CHOO = rec("NSOC48132-21PE", "JIMMY CHOO", "JIMMY CHOO I WANT CHOO EAU DE PARFUM",
            "TITULAR DEMO SEIS S.A.C", "20999999906");
    static final NsoRecord TORINO24 = rec("NSOC79215-26PE", "XERJOFF", "TORINO24", PU, PU_RUC);
    static final NsoRecord TORINO_21 = rec("NSOC62977-24PE", "XERJOFF", "TORINO 21", PU, PU_RUC);
    static final NsoRecord EAU_SAVAGE = rec("NSOC11408-12PE", "CHRISTIAN DIOR", "DIOR EAU SAVAGE EDT 100 ML VAPO", PU, PU_RUC);
    static final NsoRecord GOOD_GIRL = rec("NSOC27268-16PE", "CAROLINA HERRERA", "CH GOODGIRL EDP NS 50ML", PUIG, PUIG_RUC);
    static final NsoRecord SISTERLAND = rec("NSOC47039-20PE", "BENETTON", "BNT SISTERLAND RED ROSE EDT 80ML VP", PUIG, PUIG_RUC);
    static final NsoRecord BNT_RASPBERRY = rec("NSOC47042-20PE", "BENETTON", "BNT SISTERLAND P.RASPBERRY EDT 80ML TT", PUIG, PUIG_RUC);
    static final NsoRecord BNT_JASMINE = rec("NSOC47316-20PE", "BENETTON", "BNT SISTERLAND G.JASMINE EDT 80 ML TT", PUIG, PUIG_RUC);
    static final NsoRecord EXPLORER_BLUE = rec("NSOC49046-21PE", "MONTBLANC", "MONTBLANC EXPLORER ULTRA BLUE EAU DE PARFUM",
            "TITULAR DEMO SEIS S.A.C", "20999999906");
    static final NsoRecord WANTED_CO = rec("NSOC17744-22CO", "AZZARO", "AZZ WANTED EDP V50ML /MAD", PU, PU_RUC);
    static final NsoRecord AZZ_TMOST = rec("NSOC62013-23PE", "AZZARO", "AZZ TMOST WANTED EDTI V100ML TSTET", PU, PU_RUC);
    static final NsoRecord AZZ_FOREVER = rec("NSOC68727-24PE", "AZZARO", "AZZ WANTED FOREVER ELIXIR V100ML", PU, PU_RUC);
    static final NsoRecord CHANEL_EDP = rec("NSOC51488-21PE", "CHANEL", "AGUA DE PERFUME", PU, PU_RUC);
    static final NsoRecord CHANEL_EDT = rec("NSOC29687-17PE", "CHANEL", "AGUA DE TOCADOR", PU, PU_RUC);
    static final NsoRecord CHANEL_OIL = rec("NSOC79724-26PE", "CHANEL", "ACEITE PARA EL CUERPO", PU, PU_RUC);
    /** Fila Aduanet: sin titular ni RUC. */
    static final NsoRecord COACH_ADUANET = rec("NSOC70409-25PE", "COACH", "COACH EDT", null, null);

    static final List<NsoRecord> CATALOG = List.of(
            SUPREMACY_SILVER, SUPREMACY_GALA, NUEVE_AM_FEMME,
            KING, ICON_EDP, ICON_EDT, ICON_ELIXIR, ICON_FEM, AB_BLUE, AB_BLACK,
            CDN_WOMAN, CDN_OUD, CDN_INTEN_W, CDN_INT200, CDN_INT_MA, CDN_MAN_EDT, WILD_ONE, MANDARIN_SK,
            YARA_CANDY_MORYA, YARA_CANDY_BEAUTY, YARA_ELIXIR, YARA_MOI, TERIAQ, AJWAD,
            SCANDAL_EDP, LM_EDP, PHANTOM_EDT, PHANTOM_PARFUM, PHANTOM_RE25,
            BRIGHT_CRYSTAL_EDT, I_WANT_CHOO, TORINO24, TORINO_21, EAU_SAVAGE, GOOD_GIRL, SISTERLAND, BNT_RASPBERRY, BNT_JASMINE,
            EXPLORER_BLUE, WANTED_CO, AZZ_TMOST, AZZ_FOREVER, CHANEL_EDP, CHANEL_EDT, CHANEL_OIL, COACH_ADUANET);

    private static long nextId = 1;

    // =====================================================================
    // AUTOMATICOS
    // =====================================================================

    @Test
    void supremacySilverAutomatico() {
        Result r = matcher(CATALOG).resolve(zimaxx("Afnan", "Supremacy Silver 3.4 Oz Edp Men"));
        assertConNso(r, "NSOC77051-25PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(r.candidates.isEmpty());
        assertEquals("afnan", r.brandKey);
    }

    @Test
    void kingOfSeductionAutomatico() {
        assertConNso(matcher(CATALOG).resolve(zimaxx("Antonio Banderas", "King Of Seduction 3.4 Oz Edt Men")),
                "NSOC49004-21PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void clubDeNuitWomenAutomatico() {
        assertConNso(matcher(CATALOG).resolve(zimaxx("Armaf", "Club De Nuit 3.6 Oz Edp Women")),
                "NSOC72246-25PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void theIconEdtAutomatico() {
        assertConNso(matcher(CATALOG).resolve(zimaxx("Antonio Banderas", "The Icon 3.4 Oz Edt Men")),
                "NSOC44933-20PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void clubDeNuitIntenseWomenGanaElGeneroExplicito() {
        Result r = matcher(CATALOG).resolve(zimaxx("Armaf", "Club De Nuit Intense 3.6 Oz Edp Women"));
        assertConNso(r, "NSOC69229-24PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void yaraCandyEligeUnCodigoDeterminista() {
        Result r = matcher(CATALOG).resolve(zimaxx("Lattafa", "Yara Candy 3.4 Oz Edp Women"));
        assertConNso(r, "NSOC74343-25PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(r.renewalCodes.contains("NSOC76672-25PE"), "el otro codigo del mismo nombre queda como renovacion");
    }

    // =====================================================================
    // NUNCA AUTOMATICOS
    // =====================================================================

    @Test
    void clubDeNuitWomenContraOudNo() {
        Result r = matcher(List.of(CDN_OUD)).resolve(zimaxx("Armaf", "Club De Nuit 3.6 Oz Edp Women"));
        assertNotConNso(r);
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertTrue(anyReason(r.candidates.get(0).reasons, "oud"), r.toString());
    }

    @Test
    void theIconEdtContraSoloEdpVaARevisionPorConcentracion() {
        Result r = matcher(List.of(ICON_EDP, AB_BLUE, AB_BLACK)).resolve(zimaxx("Antonio Banderas", "The Icon 3.4 Oz Edt Men"));
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertTrue(anyReason(r.reasons, "concentración distinta"), r.toString());
        assertEquals("NSOC52223-22PE", r.candidates.get(0).code);
    }

    @Test
    void queenContraKingNiSiquieraEsCandidato() {
        Result r = matcher(List.of(KING)).resolve(zimaxx("Antonio Banderas", "Queen Of Seduction 2.7 Oz Edp Women"));
        assertTrue(r.candidates.isEmpty(), r.toString());
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, r.status);
    }

    @Test
    void nueveAmUnisexContraPourFemme() {
        Result r = matcher(CATALOG).resolve(zimaxx("Afnan", "9 Am 3.4 Oz Edp Unisex"));
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertEquals("NSOC81196-26PE", r.candidates.get(0).code, "9 am se une a 9am");
        assertTrue(anyReason(r.reasons, "género distinto: unisex vs mujer"), r.toString());
    }

    @Test
    void yaraContraSoloYaraCandy() {
        Result r = matcher(List.of(YARA_CANDY_MORYA)).resolve(zimaxx("Lattafa", "Yara 3.4 Oz Edp Women"));
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertTrue(anyReason(r.candidates.get(0).reasons, "candy"), r.toString());
    }

    @Test
    void bodySprayContraPerfume() {
        Result r = matcher(CATALOG).resolve(oasis("Armaf", "Armaf Odyssey Wild one Body Spray M 6.8oz", "PERF-ARMA-99"));
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertEquals("NSOC74282-25PE", r.candidates.get(0).code);
        assertTrue(anyReason(r.reasons, "presentación distinta"), r.toString());
    }

    @Test
    void setDeScandalVaARevision() {
        Result r = matcher(CATALOG).resolve(fragsense("JEAN PAUL GAULTIER SCANDAL FOR HIM 100ML EDT + 20ML EDP SPRAY"));
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertEquals("jean paul gaultier", r.brandKey);
        assertTrue(anyReason(r.reasons, "es un set"), r.toString());
    }

    @Test
    void hermanosHombreYMujerContraNsoSinGenero() {
        Evidence women = zimaxx("Antonio Banderas", "The Icon Women 3.4 Oz Edp");
        Evidence men = zimaxx("Antonio Banderas", "The Icon 3.4 Oz Edp Men");
        NsoMatcher m = new NsoMatcher(NsoMatcher.Index.builder().records(List.of(ICON_EDP, AB_BLUE, AB_BLACK))
                .siblings(List.of(women, men)).currentYear(2026).build());
        for (Evidence e : List.of(women, men)) {
            Result r = m.resolve(e);
            assertEquals(ProductNso.STATUS_EN_REVISION, r.status, r.toString());
            assertTrue(anyReason(r.reasons, "existe versión hombre y mujer"), r.toString());
        }
    }

    @Test
    void hermanoUnisexNoBloquea() {
        // Zimaxx dice Unisex y FragranceSense dice Women: es el mismo Teriaq etiquetado distinto.
        Evidence zx = zimaxx("Lattafa", "Teriaq 3.4 Oz Edp Unisex");
        Evidence fs = fragsense("LATTAFA TERIAQ WOMEN 100ML EDP SPRAY");
        NsoMatcher m = new NsoMatcher(NsoMatcher.Index.builder().records(CATALOG)
                .siblings(List.of(zx, fs)).currentYear(2026).build());
        assertConNso(m.resolve(zx), "NSOC70715-25PE", ProductNso.MATCHED_NOMBRE);
        assertConNso(m.resolve(fs), "NSOC70715-25PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void supremacyGalaEdpContraExtracto() {
        Result r = matcher(CATALOG).resolve(oasis("Afnan", "Afnan Supremacy Gala W EDP 3.0 oz (tester)", "PERF-AFNA-38"));
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertTrue(anyReason(r.reasons, "concentración distinta: EDP vs PARFUM"), r.toString());
        assertTrue(anyReason(r.candidates.get(0).reasons, "es tester"), r.toString());
    }

    @Test
    void nombreDeAduanasCortado() {
        Result r = matcher(CATALOG).resolve(zimaxx("Armaf", "Odyssey Mandarin Sky 3.4 Oz Edp Men"));
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertEquals("NSOC72991-25PE", r.candidates.get(0).code);
        assertTrue(anyReason(r.reasons, "cortado a 35 letras"), r.toString());
    }

    // ---- falsos positivos encontrados en la calibracion con archivos reales ----

    @Test
    void phantomParfumNoEsElPhantomBase() {
        Result soloBase = matcher(List.of(PHANTOM_RE25)).resolve(zimaxx("Paco Rabanne", "Phantom Parfum 3.4 Oz Men"));
        assertNotConNso(soloBase);
        assertTrue(anyReason(soloBase.reasons, "PARFUM"), soloBase.toString());
        // Con los registros correctos, cada version va a su codigo.
        assertConNso(matcher(CATALOG).resolve(zimaxx("Paco Rabanne", "Phantom Parfum 3.4 Oz Men")),
                "NSOC58456-23PE", ProductNso.MATCHED_NOMBRE);
        Evidence edt = zimaxx("Paco Rabanne", "Phantom 3.4 Oz Edt Men");
        Evidence parfum = zimaxx("Paco Rabanne", "Phantom Parfum 3.4 Oz Men");
        NsoMatcher m = new NsoMatcher(NsoMatcher.Index.builder().records(CATALOG)
                .siblings(List.of(edt, parfum)).currentYear(2026).build());
        assertConNso(m.resolve(edt), "NSOC48747-21PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void existenOtrasConcentracionesYLaNsoNoDiceCual() {
        Evidence edt = zimaxx("Paco Rabanne", "Phantom 3.4 Oz Edt Men");
        Evidence parfum = zimaxx("Paco Rabanne", "Phantom Parfum 3.4 Oz Men");
        NsoMatcher m = new NsoMatcher(NsoMatcher.Index.builder().records(List.of(PHANTOM_RE25))
                .siblings(List.of(edt, parfum)).currentYear(2026).build());
        Result r = m.resolve(edt);
        assertNotConNso(r);
        assertTrue(anyReason(r.reasons, "otra concentración"), r.toString());
    }

    @Test
    void brightCrystalParfumeNoEsEdt() {
        assertNotConNso(matcher(CATALOG).resolve(fragsense("VERSACE BRIGHT CRYSTAL WOMEN 90ML PARFUME SPRAY TESTER")));
        assertConNso(matcher(CATALOG).resolve(fragsense("VERSACE BRIGHT CRYSTAL WOMEN 90ML EDT SPRAY TESTER")),
                "NSOC62198-23PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void leMaleLePerfumeNoEsLeMale() {
        assertNotConNso(matcher(CATALOG).resolve(fragsense("JEAN PAUL GAULTIER LE MALE LE PERFUME 75ML EDP SPRAY *")));
    }

    @Test
    void nombreRecortadoDelProductoNoGana() {
        // Product.name = cleanName(titulo) borra "Parfum": "I Want Choo Le". Se compara con el titulo completo.
        assertNotConNso(matcher(CATALOG).resolve(zimaxx("Jimmy Choo", "I Want Choo Le Parfum 3.4 Oz  Women")));
        assertConNso(matcher(CATALOG).resolve(zimaxx("Jimmy Choo", "I Want Choo 3.4 Oz Edp Women")),
                "NSOC48132-21PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void numerosDistintosNoSonTypo() {
        assertNotConNso(matcher(List.of(TORINO24)).resolve(zimaxx("Xerjoff", "Torino21 3.4 Oz Edp Unisex")));
        assertConNso(matcher(CATALOG).resolve(zimaxx("Xerjoff", "Torino21 3.4 Oz Edp Unisex")),
                "NSOC62977-24PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void eauSauvageNoEsSauvage() {
        assertNotConNso(matcher(List.of(EAU_SAVAGE)).resolve(zimaxx("Christian Dior", "Sauvage 3.4 Oz Edt Men")));
        assertConNso(matcher(List.of(EAU_SAVAGE)).resolve(zimaxx("Christian Dior", "Eau Sauvage 3.4 Oz Edt Men")),
                "NSOC11408-12PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void millionGoldDeMujerNoEsElDeHombre() {
        // "MILLION GFH" = Million Gold For Her (90 ml); "MILLION GOLD RE25 100ML" es el de hombre.
        NsoRecord goldHombre = rec("NSOC72438-25PE", "RABANNE", "MILLION GOLD RE25 100ML", PUIG, PUIG_RUC);
        NsoRecord goldMujer = rec("NSOC72031-25PE", "RABANNE", "MILLION GFH LE26 90ML", PUIG, PUIG_RUC);
        NsoMatcher m = matcher(List.of(goldHombre, goldMujer, PHANTOM_EDT));
        assertConNso(m.resolve(zimaxx("Paco Rabanne", "Million Gold 3.0 Oz Edp Women Refillable")),
                "NSOC72031-25PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void cambioDeLetraEnNombreCortoNoEsTypo() {
        assertNotConNso(matcher(CATALOG).resolve(fragsense("LATTAFA PRIDE AJWAA 90ML EDP SPRAY")));
    }

    // =====================================================================
    // OTROS
    // =====================================================================

    @Test
    void chanelSoloGenericosEsMarcaConNso() {
        Result r = matcher(CATALOG).resolve(zimaxx("Chanel", "Coco Mademoiselle 3.4 Oz Edp Women"));
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, r.status);
        assertTrue(r.candidates.isEmpty());
        assertEquals(3, r.genericRecords);
        assertEquals(1, r.brandTitulares.size());
        assertEquals(PU, r.brandTitulares.get(0).titular);
        assertEquals(PU_RUC, r.brandTitulares.get(0).ruc);
        assertEquals(3, r.brandTitulares.get(0).codes.size());
    }

    @Test
    void titularDesconocidoDeAduanet() {
        Result r = matcher(CATALOG).resolve(zimaxx("Coach", "Dreams 3.0 Oz Edp Women"));
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, r.status);
        assertEquals(1, r.unknownTitularCodes);
        assertEquals("titular desconocido (Aduanet)", r.brandTitulares.get(0).titular);
    }

    @Test
    void marcaDesconocidaEsSinNso() {
        Result r = matcher(CATALOG).resolve(zimaxx("New Brand", "Master Of Essence 3.3 Oz Edt Men"));
        assertEquals(ProductNso.STATUS_SIN_NSO, r.status);
        assertEquals("new brand", r.brandKey);
        assertTrue(r.brandTitulares.isEmpty());
        Result typo = matcher(CATALOG).resolve(fragsense("LATTAF YARA 100ML EDP SPRAY"));
        assertEquals(ProductNso.STATUS_SIN_NSO, typo.status);
        assertEquals("LATTAFA", typo.suggestedBrand);
    }

    @Test
    void resolucionDeMarcas() {
        NsoBrandDictionary d = matcher(CATALOG).index().brands();
        assertEquals("montblanc", d.resolve("Mont Blanc", "Explorer 3.3 Oz Edp Men").brandKey);
        assertEquals("benetton", d.resolve("United Colors of Benetton", "Sisterland").brandKey);
        assertEquals("carolina herrera", d.resolve("CAROLINA", "HERRERA GOOD GIRL 80ML EDP").brandKey);
        assertEquals("christian dior", d.resolve("Dior").brandKey, "DIOR y CHRISTIAN DIOR son un grupo");
        assertEquals("paco rabanne", d.resolve("Rabanne").brandKey);
        assertEquals("dolce and gabbana", d.resolve("Dolce & Gabbana").brandKey);
        assertNull(d.resolve("Tommy Bahama", "Set Sail").brandKey);
        assertTrue(d.learnedAbbreviations().isEmpty() || !d.learnedAbbreviations().containsKey("my"));

        assertConNso(matcher(CATALOG).resolve(zimaxx("United Colors of Benetton", "Sisterland Red Rose 2.7 Oz Edt Women")),
                "NSOC47039-20PE", ProductNso.MATCHED_NOMBRE);
        Result gg = matcher(CATALOG).resolve(fragsense("CAROLINA HERRERA GOOD GIRL 80ML EDP SPRAY"));
        assertEquals("carolina herrera", gg.brandKey);
        assertConNso(gg, "NSOC27268-16PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void abreviaturasAprendidasDelCatalogo() {
        List<NsoRecord> recs = new ArrayList<>(CATALOG);
        recs.add(rec("NSOC34322-07PE", "ANTONIO BANDERAS", "AB BLUE EDT 100ML VAP C/20UD", PUIG, PUIG_RUC));
        recs.add(rec("NSOC26592-16PE", "ANTONIO BANDERAS", "AB BLACK EDT 100ML", PUIG, PUIG_RUC));
        recs.add(rec("NSOC11111-20PE", "GIORGIO ARMANI", "MY WAY EDP 50ML", PU, PU_RUC));
        recs.add(rec("NSOC11112-20PE", "GIORGIO ARMANI", "MY WAY EDP 90ML", PU, PU_RUC));
        recs.add(rec("NSOC11113-20PE", "GIORGIO ARMANI", "MY WAY INTENSE EDP 90ML", PU, PU_RUC));
        NsoBrandDictionary d = matcher(recs).index().brands();
        assertEquals("antonio banderas", d.learnedAbbreviations().get("ab"));
        assertFalse(d.learnedAbbreviations().containsKey("my"), "MY es palabra de producto (lista negra)");
    }

    @Test
    void gtinIgualAlEanDelRegistro() {
        NsoRecord conEan = rec("NSOC77051-25PE", "AFNAN", "AGUA DE PERFUME SUPREMACY SILVER", MORYA, MORYA_RUC);
        conEan.setEan(NsoKeys.gtinKey("6290171000976"));
        NsoMatcher m = matcher(List.of(conEan, KING));
        Evidence e = Evidence.of(1L, "Afnan", "Otro Nombre Cualquiera").gtin("6290171000976")
                .offer("Zimaxx", "ZX_PE-AFN-M-000976", "Otro Nombre Cualquiera 3.4 Oz Edp Men", "6290171000976");
        assertConNso(m.resolve(e), "NSOC77051-25PE", ProductNso.MATCHED_UPC);

        Evidence otraMarca = Evidence.of(2L, "Armaf", "Algo").gtin("6290171000976");
        Result r = m.resolve(otraMarca);
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertEquals("NSOC77051-25PE", r.candidates.get(0).code);
        assertTrue(anyReason(r.reasons, "otra marca"), r.toString());
    }

    @Test
    void aliasSkuDelProveedor() {
        NsoAlias alias = new NsoAlias(NsoAlias.KIND_SUPPLIER_SKU, "oasis|PERF-AFNA-26", "NSOC81196-26PE",
                NsoAlias.POSITIVE, NsoAlias.ORIGIN_APROBADO);
        NsoMatcher m = new NsoMatcher(NsoMatcher.Index.builder().records(CATALOG).aliases(List.of(alias))
                .currentYear(2026).build());
        Result r = m.resolve(oasis("Afnan", "Afnan 9 AM Dive U EDP 5.0 oz", "perf-afna-26 "));
        assertConNso(r, "NSOC81196-26PE", ProductNso.MATCHED_ALIAS_SKU);
    }

    @Test
    void aliasNameKeyCubreOtroTamanoPeroNoLaVersionEdt() {
        Evidence aprobado = oasis("Afnan", "Afnan Supremacy Gala W EDP 3.0 oz (tester)", "PERF-AFNA-38");
        String nameKey = matcher(CATALOG).nameKey(aprobado);
        assertEquals("afnan|gala supremacy|edp|women|perfume", nameKey);
        NsoAlias alias = new NsoAlias(NsoAlias.KIND_NAME_KEY, nameKey, "NSOC78874-26PE",
                NsoAlias.POSITIVE, NsoAlias.ORIGIN_APROBADO);
        NsoMatcher m = new NsoMatcher(NsoMatcher.Index.builder().records(CATALOG).aliases(List.of(alias))
                .currentYear(2026).build());
        assertConNso(m.resolve(zimaxx("Afnan", "Supremacy Gala 6.7 Oz Edp Women")), "NSOC78874-26PE",
                ProductNso.MATCHED_ALIAS_NOMBRE);
        assertNotConNso(m.resolve(zimaxx("Afnan", "Supremacy Gala 3.0 Oz Edt Women")));
    }

    @Test
    void aliasNegativoYParRechazadoEliminanElCandidato() {
        Evidence e = zimaxx("Antonio Banderas", "The Icon 3.4 Oz Edt Men");
        NsoMatcher base = matcher(List.of(ICON_EDP, AB_BLUE, AB_BLACK));
        assertEquals(ProductNso.STATUS_EN_REVISION, base.resolve(e).status);

        NsoAlias negativo = new NsoAlias(NsoAlias.KIND_NAME_KEY, base.nameKey(e), "NSOC52223-22PE",
                NsoAlias.NEGATIVE, NsoAlias.ORIGIN_RECHAZADO);
        NsoMatcher conNegativo = new NsoMatcher(NsoMatcher.Index.builder().records(List.of(ICON_EDP, AB_BLUE, AB_BLACK))
                .aliases(List.of(negativo)).currentYear(2026).build());
        Result r = conNegativo.resolve(e);
        assertTrue(r.candidates.isEmpty(), r.toString());
        assertEquals(ProductNso.STATUS_MARCA_CON_NSO, r.status);

        NsoMatcher conRechazo = new NsoMatcher(NsoMatcher.Index.builder().records(List.of(ICON_EDP, AB_BLUE, AB_BLACK))
                .rejectedPair(e.productId, "NSOC52223-22PE").currentYear(2026).build());
        assertTrue(conRechazo.resolve(e).candidates.isEmpty());
    }

    @Test
    void investigacionConOtroCodigoVaARevisionConOrigenResearch() {
        Evidence e = zimaxx("Armaf", "Club De Nuit 3.6 Oz Edp Women");
        e.research("NSOC76211-25PE", "ALTA · Club De Nuit 3.6 Oz Edp Women");
        Result r = matcher(CATALOG).resolve(e);
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        assertNull(r.nsoCode);
        Candidate research = r.candidates.stream().filter(c -> NsoCandidate.ORIGIN_RESEARCH.equals(c.origin))
                .findFirst().orElseThrow();
        assertEquals("NSOC76211-25PE", research.code);
        assertTrue(anyReason(research.reasons, "tu investigación anterior sugería NSOC76211-25PE (ALTA)"), r.toString());
        assertTrue(r.candidates.stream().anyMatch(c -> c.code.equals("NSOC72246-25PE")
                && NsoCandidate.ORIGIN_MATCHER.equals(c.origin)));

        // Igual via alias RESEARCH del indice (SKU de la hoja "Con NSO").
        NsoAlias alias = new NsoAlias(NsoAlias.KIND_SUPPLIER_SKU, NsoKeys.skuKey("Zimaxx", e.offers.get(0).supplierSku),
                "NSOC76211-25PE", NsoAlias.POSITIVE, NsoAlias.ORIGIN_RESEARCH);
        alias.setDetail("ALTA · Club De Nuit");
        Evidence sinHint = zimaxx("Armaf", "Club De Nuit 3.6 Oz Edp Women");
        NsoAlias aliasSku = new NsoAlias(NsoAlias.KIND_SUPPLIER_SKU, NsoKeys.skuKey("Zimaxx", sinHint.offers.get(0).supplierSku),
                "NSOC76211-25PE", NsoAlias.POSITIVE, NsoAlias.ORIGIN_RESEARCH);
        NsoMatcher m = new NsoMatcher(NsoMatcher.Index.builder().records(CATALOG).aliases(List.of(alias, aliasSku))
                .currentYear(2026).build());
        Result viaIndex = m.resolve(sinHint);
        assertEquals(ProductNso.STATUS_EN_REVISION, viaIndex.status, "la investigacion NUNCA asigna");
        assertTrue(viaIndex.candidates.stream().anyMatch(c -> NsoCandidate.ORIGIN_RESEARCH.equals(c.origin)));

        // Si la investigacion dice el mismo codigo: se mantiene y se anota.
        Evidence igual = zimaxx("Armaf", "Club De Nuit 3.6 Oz Edp Women").research("NSOC72246-25PE", "ALTA · x");
        Result ok = matcher(CATALOG).resolve(igual);
        assertConNso(ok, "NSOC72246-25PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(anyReason(ok.reasons, "coincide con tu investigación"));

        // Otra renovacion del mismo nombre no contradice.
        Evidence renov = zimaxx("Lattafa", "Yara Candy 3.4 Oz Edp Women").research("NSOC76672-25PE", "MEDIA · x");
        assertConNso(matcher(CATALOG).resolve(renov), "NSOC74343-25PE", ProductNso.MATCHED_NOMBRE);
    }

    @Test
    void decisionBloqueada() {
        Evidence e = zimaxx("Armaf", "Club De Nuit 3.6 Oz Edp Women").locked(ProductNso.STATUS_CON_NSO,
                "NSOC76211-25PE", ProductNso.MATCHED_MANUAL);
        assertConNso(matcher(CATALOG).resolve(e), "NSOC76211-25PE", ProductNso.MATCHED_MANUAL);

        NsoRecord inactivo = rec("NSOC76211-25PE", "ARMAF", "PERFUME- CLUB DE NUIT OUD 105ML", MORYA, MORYA_RUC);
        inactivo.setActive(false);
        List<NsoRecord> recs = new ArrayList<>(CATALOG);
        recs.remove(CDN_OUD);
        recs.add(inactivo);
        Result r = matcher(recs).resolve(e);
        assertConNso(r, "NSOC72246-25PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(anyReason(r.reasons, "ya no está activo"), r.toString());
    }

    @Test
    void codigosDeOtroPais() {
        Evidence e = zimaxx("Azzaro", "Wanted 3.4 Oz Edp Men");
        Result acepta = matcher(CATALOG).resolve(e);
        assertConNso(acepta, "NSOC17744-22CO", ProductNso.MATCHED_NOMBRE);
        assertTrue(anyReason(acepta.reasons, "otro país (CO)"));

        NsoMatcher soloPeru = new NsoMatcher(NsoMatcher.Index.builder().records(CATALOG).acceptCanCodes(false)
                .currentYear(2026).build());
        Result r = soloPeru.resolve(e);
        assertNotConNso(r);
        assertTrue(anyReason(r.reasons, "otro país"), r.toString());
    }

    @Test
    void filaDePreviewUsaLaMismaLogica() {
        NsoMatcher m = matcher(CATALOG);
        Result preview = m.resolvePreviewRow("Supremacy Silver 3.4 Oz Edp Men", "Afnan", "ZX_PE-AFN-M-000976",
                "6290171000976", "Zimaxx");
        assertConNso(preview, "NSOC77051-25PE", ProductNso.MATCHED_NOMBRE);
        Result preview2 = m.resolvePreviewRow("Afnan Supremacy Gala W EDP 3.0 oz (tester)", "Afnan", "PERF-AFNA-38",
                null, "Oasis");
        assertEquals(ProductNso.STATUS_EN_REVISION, preview2.status);
    }

    @Test
    void deterministaConRegistrosBarajados() {
        List<Evidence> evidences = List.of(
                zimaxx("Lattafa", "Yara Candy 3.4 Oz Edp Women"),
                zimaxx("Armaf", "Club De Nuit Intense 3.6 Oz Edp Women"),
                zimaxx("Antonio Banderas", "The Icon Splendid 3.4 Oz Edp Women"),
                zimaxx("Armaf", "Club De Nuit Milestone 3.6 Oz Edp Unisex"),
                zimaxx("Chanel", "Chance 3.4 Oz Edp Women"));
        List<String> expected = null;
        Random rnd = new Random(42);
        for (int i = 0; i < 15; i++) {
            List<NsoRecord> shuffled = new ArrayList<>(CATALOG);
            Collections.shuffle(shuffled, rnd);
            NsoMatcher m = matcher(shuffled);
            List<String> got = new ArrayList<>();
            for (Evidence e : evidences) {
                Result r = m.resolve(e);
                got.add(r.status + "|" + r.nsoCode + "|" + r.candidates + "|" + r.reasons + "|" + r.brandTitulares.size());
            }
            if (expected == null) expected = got;
            else assertEquals(expected, got, "iteracion " + i);
        }
    }

    // =====================================================================
    // Revision final (calibracion): Light Blue, Qimmah "L", typos a la vista
    // =====================================================================

    static final NsoRecord LB = rec("NSOC68109-24PE", "DOLCE & GABBANA", "LIGHT BLUE", PU, PU_RUC);
    static final NsoRecord LB_PH = rec("NSOC68266-24PE", "DOLCE & GABBANA", "LIGHT BLUE POUR HOMME", PU, PU_RUC);
    static final NsoRecord LB_EDT_PH = rec("NSOC58105-23PE", "DOLCE & GABBANA", "EAU DE TOILETTE POUR HOMME LIGHT BLUE", PU, PU_RUC);
    static final NsoRecord LB_WOM = rec("NSOC79422-26PE", "DOLCE & GABBANA",
            "DOLCE & GABBANA LIGHT BLUE EDP 100ML WOM REG", PU, PU_RUC);
    static final List<NsoRecord> LIGHT_BLUE = List.of(LB, LB_PH, LB_EDT_PH, LB_WOM);
    static final NsoRecord QIMMAH_L = rec("NSOC79787-26PE", "LATTAFA", "LATTAFA QIMMAH 3.4 EDP L (126994)", BEAUTY, BEAUTY_RUC);
    static final NsoRecord ESSENCE = rec("NSOC76383-25PE", "BHARARA", "PERFUME- ESSENCE 100ML", MORYA, MORYA_RUC);
    static final NsoRecord TUBBEES_BUBBLE_GUM = rec("NSOC71423-25PE", "GRANDEUR", "AGUA DE PERFUME-TUBBEES BUBBLE GUM",
            MORYA, MORYA_RUC);

    @Test
    void lightBlueEdtPrefiereLaNsoQueDeclaraEdtAunqueHayaOtraMasNueva() {
        // Antes: «LIGHT BLUE POUR HOMME» (2024, sin concentracion) le ganaba a «EAU DE TOILETTE POUR HOMME LIGHT
        // BLUE» (2023, EDT) solo por el anio. El proveedor dice EDT: gana la que declara EDT.
        Result r = matcher(LIGHT_BLUE).resolve(zimaxx("Dolce & Gabbana", "Light Blue 4.2 Oz Edt Men"));
        assertConNso(r, "NSOC58105-23PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(anyReason(r.reasons, "misma concentración (EDT)"), r.toString());
        assertTrue(anyReason(r.reasons, "se eligió la que declara esa misma concentración"), r.toString());
        assertFalse(anyReason(r.reasons, "renovaciones"), "no se afirma que sean renovaciones: " + r);
        assertTrue(r.renewalCodes.contains("NSOC68266-24PE"), r.toString());

        // Sin concentracion en el proveedor: sigue eligiendo la mas reciente (comportamiento de siempre).
        Result sinConc = matcher(List.of(LB_PH, LB_EDT_PH)).resolve(zimaxx("Dolce & Gabbana", "Light Blue Pour Homme Men"));
        assertConNso(sinConc, "NSOC68266-24PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(anyReason(sinConc.reasons, "hay 2 NSO con este mismo nombre: se eligió la más reciente"), sinConc.toString());
    }

    @Test
    void registroSinGeneroConVersionDelOtroGeneroEnElCatalogoVaARevision() {
        // El Excel del mes trae SOLO la version mujer (sin hermano hombre en el archivo): igual no es automatico,
        // porque el catalogo NSO tiene «LIGHT BLUE POUR HOMME» y «LIGHT BLUE» no dice cual es.
        Result r = matcher(LIGHT_BLUE).resolve(zimaxx("Dolce & Gabbana", "Light Blue 3.3 Oz Edt Women"));
        assertNotConNso(r);
        assertEquals(ProductNso.STATUS_EN_REVISION, r.status);
        Candidate lb = r.candidates.stream().filter(c -> c.code.equals("NSOC68109-24PE")).findFirst().orElseThrow();
        assertTrue(anyReason(lb.reasons, "existe versión hombre y mujer; la NSO no dice cuál"), r.toString());
    }

    @Test
    void laLDeLadiesEnElNombreDeclaradoEsMujer() {
        // «LATTAFA QIMMAH 3.4 EDP L (126994)»: la L es la version mujer (como la M de «ODYSSEY HOMME 6.8 EDP M»).
        assertConNso(matcher(List.of(QIMMAH_L)).resolve(zimaxx("Lattafa", "Qimmah 3.4 Oz  Edp Women")),
                "NSOC79787-26PE", ProductNso.MATCHED_NOMBRE);
        // Una lista que trae SOLO la version hombre: nunca se le asigna la inscripcion de mujer.
        Result men = matcher(List.of(QIMMAH_L)).resolve(zimaxx("Lattafa", "Qimmah 3.4 Oz Edp Men"));
        assertNotConNso(men);
        assertEquals(ProductNso.STATUS_EN_REVISION, men.status);
        assertTrue(anyReason(men.candidates.get(0).reasons, "género distinto: hombre vs mujer"), men.toString());
    }

    @Test
    void typoAceptadoQuedaEnLosMotivosYConUnaSolaPalabraVaARevision() {
        // Dior tiene «Sauvage» y «Eau Sauvage»: si coincidio gracias a sauvage ≈ savage, que se vea.
        Result sauvage = matcher(List.of(EAU_SAVAGE)).resolve(zimaxx("Christian Dior", "Eau Sauvage 3.4 Oz Edt Men"));
        assertConNso(sauvage, "NSOC11408-12PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(anyReason(sauvage.reasons, "se aceptó una diferencia de una letra: sauvage ≈ savage"), sauvage.toString());

        Result tubbees = matcher(List.of(TUBBEES_BUBBLE_GUM))
                .resolve(oasis("Grandeur", "Grandeur Tubbes Bubble Gum U EDP 3.4oz", "PERF-GRAN-7"));
        assertConNso(tubbees, "NSOC71423-25PE", ProductNso.MATCHED_NOMBRE);
        assertTrue(anyReason(tubbees.reasons, "se aceptó una diferencia de una letra: tubbes ≈ tubbees"), tubbees.toString());

        // «Essense» vs «PERFUME- ESSENCE 100ML»: el nombre es UNA palabra y difiere en una letra -> revision.
        Result essense = matcher(List.of(ESSENCE)).resolve(zimaxx("Bharara", "Essense 3.4 Oz Edp Unisex"));
        assertNotConNso(essense);
        assertEquals(ProductNso.STATUS_EN_REVISION, essense.status);
        assertEquals("NSOC76383-25PE", essense.candidates.get(0).code);
        assertTrue(anyReason(essense.reasons, "el nombre es una sola palabra y difiere en una letra"), essense.toString());
    }

    // =====================================================================
    // utilidades
    // =====================================================================

    static NsoRecord rec(String code, String brand, String declared, String titular, String ruc) {
        NsoRecord r = new NsoRecord(code, brand, NsoKeys.brandKey(brand), declared);
        r.setTitular(titular);
        r.setRuc(ruc);
        r.setCountry(NsoCode.country(code));
        r.setNsoYear(NsoCode.year(code, 2026));
        r.setSource(titular == null ? NsoRecord.SOURCE_ADUANET : NsoRecord.SOURCE_XLSX);
        return r;
    }

    static NsoMatcher matcher(List<NsoRecord> records) {
        return new NsoMatcher(NsoMatcher.Index.builder().records(records).currentYear(2026).build());
    }

    /** Fila Zimaxx tal como queda en BD: marca en su columna, name = cleanName(titulo), titulo en la oferta. */
    static Evidence zimaxx(String brand, String title) {
        long id = nextId++;
        return Evidence.of(id, brand, PerfumeNormalizer.cleanName(title))
                .offer("Zimaxx", "ZX-" + id, title, null)
                .ml(PerfumeNormalizer.mlFromOz(title))
                .forma(PerfumeNormalizer.detectForma(title));
    }

    /** Fila Oasis: marca repetida al inicio del titulo, SKU estable, sin UPC. */
    static Evidence oasis(String brand, String title, String sku) {
        return Evidence.of(nextId++, brand, PerfumeNormalizer.cleanName(PerfumeNormalizer.stripBrandPrefix(brand, title)))
                .offer("Oasis Perfumes", sku, title, null)
                .forma(PerfumeNormalizer.detectForma(title));
    }

    /** Fila FragranceSense: sin columna marca -> marca = primera palabra (como GenericSupplierParser). */
    static Evidence fragsense(String desc) {
        String brand = desc.trim().split("\\s+")[0];
        return Evidence.of(nextId++, brand, PerfumeNormalizer.cleanName(PerfumeNormalizer.stripBrandPrefix(brand, desc)))
                .offer("FragranceSense", null, desc, null)
                .forma(PerfumeNormalizer.detectForma(desc));
    }

    static void assertConNso(Result r, String code, String matchedBy) {
        assertEquals(ProductNso.STATUS_CON_NSO, r.status, r.toString());
        assertEquals(code, r.nsoCode, r.toString());
        assertEquals(matchedBy, r.matchedBy, r.toString());
    }

    static void assertNotConNso(Result r) {
        assertNotEquals(ProductNso.STATUS_CON_NSO, r.status, r.toString());
        assertNull(r.nsoCode, r.toString());
    }

    static boolean anyReason(List<String> reasons, String fragment) {
        return reasons.stream().anyMatch(s -> s.contains(fragment));
    }
}
