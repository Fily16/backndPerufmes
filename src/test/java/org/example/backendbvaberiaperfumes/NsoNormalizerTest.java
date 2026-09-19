package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.service.nso.NsoNormalizer;
import org.example.backendbvaberiaperfumes.service.nso.NsoNormalizer.NormalizedName;
import org.example.backendbvaberiaperfumes.service.nso.NsoText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Normalizador de nombres NSO (puro, sin Spring). Casos con textos REALES de aduanas y proveedores. */
class NsoNormalizerTest {

    @Test
    void aduanasNueveAmPourFemme() {
        NormalizedName n = NsoNormalizer.normalize("AGUA DE PERFUME - 9AM POUR FEMME", List.of("afnan"));
        assertEquals(List.of("9am"), n.core());
        assertEquals(NsoNormalizer.EDP, n.concentration);
        assertEquals(NsoNormalizer.WOMEN, n.gender);
        assertTrue(n.genderInName, "femme es parte del nombre");
    }

    @Test
    void extractoDePerfumeEsParfum() {
        NormalizedName n = NsoNormalizer.normalize("EXTRACTO DE PERFUME-SUPREMACY GALA", List.of("afnan"));
        assertEquals(NsoNormalizer.PARFUM, n.concentration);
        assertEquals(List.of("gala", "supremacy"), n.core());
    }

    @Test
    void abreviaturaDeMarcaYTamano() {
        NormalizedName n = NsoNormalizer.normalize("AB THE ICON EDT 200ML", List.of("antonio banderas", "ab"));
        assertEquals(List.of("icon"), n.core());
        assertEquals(NsoNormalizer.EDT, n.concentration);
        assertEquals(200, n.ml);
    }

    @Test
    void tester() {
        assertTrue(NsoNormalizer.normalize("GU GUESS AMORE CAPRI EDT 100ML TST", List.of("guess", "gu")).tester);
        assertTrue(NsoNormalizer.normalize("Afnan Supremacy Gala W EDP 3.0 oz (tester)").tester);
        assertFalse(NsoNormalizer.normalize("Supremacy Silver 3.4 Oz Edp Men").tester);
    }

    @Test
    void ruidoDeAduanas() {
        assertEquals(List.of("fame"), NsoNormalizer.normalize("FAME RE26 80ML REFILLABLE").core());
        assertEquals(List.of("club", "ico", "nuit"), NsoNormalizer.normalize("AGUA DE PERFUME-CLUB DE NUIT ICO105").core());
        assertEquals(105, NsoNormalizer.normalize("AGUA DE PERFUME-CLUB DE NUIT ICO105").ml);
        NormalizedName azz = NsoNormalizer.normalize("AZZ WANTED EDP V50ML /MAD", List.of("azzaro", "azz"));
        assertEquals(List.of("wanted"), azz.core());
        assertEquals(50, azz.ml);
        assertEquals(List.of("pi"), NsoNormalizer.normalize("PI EDT 100ML R2").core(), "R2 es ruido");
        assertEquals(List.of("male"), NsoNormalizer.normalize("JPG LM EDT 200ML RPK 2017", List.of("jpg")).core());
        assertEquals(List.of("qimmah"), NsoNormalizer.normalize("LATTAFA QIMMAH 3.4 EDP L (126994)", List.of("lattafa")).core());
    }

    @Test
    void set() {
        assertEquals(NsoNormalizer.FORMA_SET, NsoNormalizer.normalize("212 VIP ROSÉ EDP 80ML+ BL 100+ MGSP MD26").forma);
        assertEquals(NsoNormalizer.FORMA_SET,
                NsoNormalizer.normalize("JEAN PAUL GAULTIER SCANDAL FOR HIM 100ML EDT + 20ML EDP SPRAY").forma);
        assertEquals(NsoNormalizer.FORMA_SET, NsoNormalizer.normalize("Set Onyx 5pc 3.4 Oz Edp Men").forma);
    }

    @Test
    void onzasAMl() {
        assertEquals(100, NsoNormalizer.normalize("First Instinct Blue 3.4 Oz Edp Women").ml);
    }

    @Test
    void oasisConGeneroSueltoYMarcaRepetida() {
        NormalizedName n = NsoNormalizer.normalize("Afnan 9 AM Dive U EDP 5.0 oz", List.of("afnan"));
        assertEquals(List.of("9", "am", "dive"), n.core());
        assertEquals(NsoNormalizer.UNISEX, n.gender);
        assertEquals(150, n.ml);
        assertEquals(NsoNormalizer.EDP, n.concentration);
    }

    @Test
    void bodySpray() {
        NormalizedName n = NsoNormalizer.normalize("Armaf Odyssey Wild one Body Spray M 6.8oz", List.of("armaf"));
        assertEquals(NsoNormalizer.FORMA_BODY, n.forma);
        assertEquals(List.of("odyssey", "one", "wild"), n.core(), "body/spray no quedan como nombre");
        assertEquals(NsoNormalizer.MEN, n.gender);
        // "Cookies & Cream" es nombre, no crema corporal.
        assertEquals(NsoNormalizer.FORMA_PERFUME,
                NsoNormalizer.normalize("Grandeur Tubbes Cookies & Cream U EDP 1.7oz").forma);
        // "Burberry Body" es un perfume: body queda como nombre.
        assertEquals(List.of("body"), NsoNormalizer.normalize("Body 3.0 Oz Edp Women").core());
    }

    @Test
    void dosConcentracionesEsAmbigua() {
        assertEquals(NsoNormalizer.AMBIGUA, NsoNormalizer.normalize("EROS NAJIM PARFUM 100ML EDP SPRAY").concentration);
        assertEquals(NsoNormalizer.PARFUM,
                NsoNormalizer.normalize("VERSACE BRIGHT CRYSTAL WOMEN 90ML PARFUME SPRAY TESTER").concentration);
    }

    @Test
    void leParfumEsNombreDeVersion() {
        NormalizedName fs = NsoNormalizer.normalize("JEAN PAUL GAULTIER LE MALE LE PERFUME 75ML EDP SPRAY *",
                List.of("jean paul gaultier", "jpg"));
        assertEquals(List.of("leparfum", "male"), fs.core());
        assertEquals(NsoNormalizer.EDP, fs.concentration);
        NormalizedName ysl = NsoNormalizer.normalize("YSL MYSLF LE PARFUM V100ML TST", List.of("yves saint laurent", "ysl"));
        assertEquals(List.of("leparfum", "myslf"), ysl.core());
        assertNull(ysl.concentration);
    }

    @Test
    void eauSueltoEsNombrePeroEauDeParfumNo() {
        assertEquals(List.of("eau", "savage"), NsoNormalizer.normalize("DIOR EAU SAVAGE EDT 100 ML VAPO", List.of("dior")).core());
        assertEquals(List.of("sauvage"), NsoNormalizer.normalize("Sauvage 3.4 Oz Edt Men").core());
        assertEquals(List.of("intense", "q"), NsoNormalizer.normalize("Q EAU DE PARFUM INTENSE").core());
    }

    @Test
    void sinonimosYOneMillion() {
        assertEquals(List.of("1", "million"), NsoNormalizer.normalize("One Million 3.4 Oz Edt Men").core());
        assertEquals(List.of("1", "million"), NsoNormalizer.normalize("1M EDT SPRAY 3.4OZ / 100ML").core());
        assertEquals(List.of("blue", "double"), NsoNormalizer.normalize("BHARARA DOUBLE BLEU POUR HOMME", List.of("bharara")).core());
        assertEquals(List.of("club", "intense", "nuit"), NsoNormalizer.normalize("AGUA DE PERFUME-CLUB D NUIT INTEN W").core());
    }

    @Test
    void generoConTypoYNombreSoloGenero() {
        assertEquals(NsoNormalizer.MEN, NsoNormalizer.normalize("ARMAF ODYSSEY HOMEE WHITE EDITION 100ML EDP SPRAY").gender);
        NormalizedName azzaro = NsoNormalizer.normalize("AZZARO POUR HOMME EDT", List.of("azzaro"));
        assertEquals(List.of("homme"), azzaro.core(), "si el nombre es solo el genero, ese es el nombre");
    }

    @Test
    void nombreDeclaradoCortado() {
        NormalizedName sk = NsoNormalizer.normalizeDeclared("AGUA DE PERFUME-ODYSSEY MANDARIN SK", List.of("armaf"));
        assertEquals(35, sk.rawLength);
        assertTrue(sk.isTruncatable());
        assertTrue(sk.lastTokenIsLastWord());
        // Tamano pegado al final: el nombre esta completo.
        NormalizedName one = NsoNormalizer.normalizeDeclared("AGUA DE PERFUME-ODYSSEY WILD ONE100", List.of("armaf"));
        assertFalse(one.lastTokenIsLastWord());
        // Genero cortado al final ("INT MA" = intense man).
        NormalizedName ma = NsoNormalizer.normalizeDeclared("AGUA DE PERFUME-CLUB DE NUIT INT MA", List.of("armaf"));
        assertEquals(List.of("club", "intense", "nuit"), ma.core());
        assertEquals(NsoNormalizer.MEN, ma.gender);
    }

    @Test
    void abreviaturasDeGeneroDeImportadores() {
        NormalizedName gfh = NsoNormalizer.normalizeDeclared("MILLION GFH LE26 90ML", List.of("rabanne"));
        assertEquals(List.of("gold", "million"), gfh.core());
        assertEquals(NsoNormalizer.WOMEN, gfh.gender);
        assertEquals("MILLION GFH LE26 90ML", gfh.raw);
        assertEquals(NsoNormalizer.MEN, NsoNormalizer.normalizeDeclared("GG PH P PH NEW TST EDP 90ML 20 IV", List.of("gucci", "gg")).gender);
        assertEquals(NsoNormalizer.WOMEN,
                NsoNormalizer.normalizeDeclared("NARCISO RODRIGUEZ FH FOR HER NEW 2024 EDP 100 ML VAPO", List.of("narciso rodriguez")).gender);
    }

    @Test
    void laLSueltaDeLadiesEsMujerPeroElArticuloFrancesNo() {
        NormalizedName qimmah = NsoNormalizer.normalizeDeclared("LATTAFA QIMMAH 3.4 EDP L (126994)", List.of("lattafa"));
        assertEquals(List.of("qimmah"), qimmah.core());
        assertEquals(NsoNormalizer.WOMEN, qimmah.gender);
        assertFalse(qimmah.genderInName, "la L no es palabra del nombre (como la M de men)");
        assertEquals("LATTAFA QIMMAH 3.4 EDP L (126994)", qimmah.raw);
        assertEquals(NsoNormalizer.WOMEN, NsoNormalizer.normalizeDeclared("QIMMAH EDP L 100ML", List.of("lattafa")).gender);
        assertEquals(NsoNormalizer.MEN,
                NsoNormalizer.normalizeDeclared("ARMAF ODYSSEY HOMME 6.8 EDP M", List.of("armaf")).gender);
        // L' (articulo) seguido de otra palabra del nombre: nunca es genero (casos reales del catalogo).
        assertNull(NsoNormalizer.normalizeDeclared("ISSEY MIYAKE L EAU D ISSEY EDT 100 ML VAPO", List.of("issey miyake")).gender);
        assertNull(NsoNormalizer.normalizeDeclared("FBK L ABSOLUE EDP 100ML", List.of("kenzo")).gender);
        assertNull(NsoNormalizer.normalizeDeclared("YSL MYSLF L ABSOLU V100ML TST", List.of("yves saint laurent", "ysl")).gender);
        assertEquals(NsoNormalizer.MEN,
                NsoNormalizer.normalizeDeclared("ELIE SAAB FRAG L HOMME EDP 100 ML", List.of("elie saab")).gender);
        // En un titulo de proveedor (normalize, no normalizeDeclared) la L no se toca.
        assertNull(NsoNormalizer.normalize("Qimmah 3.4 Oz Edp L", List.of("lattafa")).gender);
    }

    @Test
    void genericoSinNombre() {
        assertTrue(NsoNormalizer.normalize("AGUA DE PERFUME", List.of("chanel")).isGeneric());
        assertTrue(NsoNormalizer.normalize("ACEITE PARA EL CUERPO", List.of("chanel")).isGeneric());
        assertTrue(NsoNormalizer.normalize("COLONIA", List.of("prada")).isGeneric());
    }

    @Test
    void textoUtilidades() {
        assertTrue(NsoText.editDistanceIsOne("tubbes", "tubbees"));
        assertTrue(NsoText.editDistanceIsOne("rougue", "rouge"));
        assertFalse(NsoText.editDistanceIsOne("rouge", "rouge"));
        assertFalse(NsoText.editDistanceIsOne("sauvage", "savag"));
        assertEquals(1.0, NsoText.jaroWinkler("montblanc", "montblanc"));
        assertTrue(NsoText.jaroWinkler("lattaf", "lattafa") > 0.9);
        assertEquals(3, NsoText.levenshtein("kitten", "sitting"));
        assertEquals(0.67, NsoText.round2(NsoText.dice(1, 1, 1, 2)));
    }
}
