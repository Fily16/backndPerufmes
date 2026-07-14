package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer.GtinResult;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer.Status;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Casos REALES tomados del analisis de los Excel de proveedores
 * (FragranceSense y Zimaxx "US Wholesale - 2K").
 */
class GtinCanonicalizerTest {

    @Test
    void ean13ValidoSeCanonicalizaA14() {
        // Lattafa Khamrah Dukhan, presente en ambos proveedores.
        GtinResult r = GtinCanonicalizer.canonicalize("6290362342373");
        assertEquals(Status.OK, r.status);
        assertEquals("06290362342373", r.canonical14);
    }

    @Test
    void nonBreakingSpaceDeFragranceSense() {
        // FS trae celdas de texto con   pegado al codigo.
        GtinResult r = GtinCanonicalizer.canonicalize(" 3614274143751");
        assertEquals(Status.OK, r.status);
        assertEquals("03614274143751", r.canonical14);
    }

    @Test
    void upcLiteralCeroEsVacio() {
        // FS usa 0 como "sin codigo".
        assertEquals(Status.EMPTY, GtinCanonicalizer.canonicalize("0").status);
        assertEquals(Status.EMPTY, GtinCanonicalizer.canonicalize(0L).status);
        assertEquals(Status.EMPTY, GtinCanonicalizer.canonicalize(null).status);
        assertEquals(Status.EMPTY, GtinCanonicalizer.canonicalize("  ").status);
    }

    @Test
    void onceDigitosDeZimaxxRecuperanElCeroInicial() {
        // Excel guarda el UPC-A como numero y pierde el 0 inicial (70 filas reales en Zimaxx).
        GtinResult r = GtinCanonicalizer.canonicalize(85715167224L);
        assertEquals(Status.OK, r.status);
        assertEquals("00085715167224", r.canonical14);
    }

    @Test
    void typoDeFragranceSenseQuedaEnCuarentena() {
        // FS: 6291106066919 es typo del EAN valido 6291106066319 (Ser Al Khulood Brown).
        GtinResult r = GtinCanonicalizer.canonicalize("6291106066919");
        assertEquals(Status.CHECKSUM_FAIL, r.status);
        assertNull(r.canonical14);
        assertEquals("6291106066919", r.rawDigits);
        // El codigo correcto si valida:
        assertEquals(Status.OK, GtinCanonicalizer.canonicalize("6291106066319").status);
    }

    @Test
    void numeroEnNotacionCientificaNoSeRompe() {
        // POI puede entregar el UPC como Double.
        GtinResult r = GtinCanonicalizer.canonicalize(3614273955546.0d);
        assertEquals(Status.OK, r.status);
        assertEquals("03614273955546", r.canonical14);
    }

    @Test
    void ean13ConCeroInicialYUpcAEquivalentesConvergen() {
        // "0036000291452" (EAN-13) y "036000291452" (UPC-A) son el mismo GTIN.
        GtinResult ean = GtinCanonicalizer.canonicalize("0036000291452");
        GtinResult upc = GtinCanonicalizer.canonicalize("036000291452");
        assertEquals(Status.OK, ean.status);
        assertEquals(Status.OK, upc.status);
        assertEquals(ean.canonical14, upc.canonical14);
    }

    @Test
    void upcEDeOchoDigitosSeExpande() {
        // 01234565: UPC-E clasico de ejemplo GS1 -> UPC-A 012000003455... comprobamos via checksum.
        GtinResult r = GtinCanonicalizer.canonicalize("01234565");
        // La expansion valida exactamente una interpretacion (UPC-E o EAN-8) o marca AMBIGUOUS.
        assertTrue(r.status == Status.OK || r.status == Status.AMBIGUOUS);
        if (r.status == Status.OK) {
            assertEquals(14, r.canonical14.length());
        }
    }

    @Test
    void largoInvalidoNoEsIdentidad() {
        assertEquals(Status.INVALID_LENGTH, GtinCanonicalizer.canonicalize("12345").status);
        assertEquals(Status.INVALID_LENGTH, GtinCanonicalizer.canonicalize("123456789").status);
        assertEquals(Status.INVALID_LENGTH, GtinCanonicalizer.canonicalize("123456789012345").status);
    }

    @Test
    void asteriscosYGuionesSeLimpian() {
        GtinResult r = GtinCanonicalizer.canonicalize(" 6290362342373* ");
        assertEquals(Status.OK, r.status);
        assertEquals("06290362342373", r.canonical14);
    }

    @Test
    void perfumeNormalizerDelegaYSoloDevuelveValidados() {
        // Codigo valido -> canonico.
        assertEquals("06290362342373", PerfumeNormalizer.gtin14("6290362342373"));
        // Typo (checksum invalido) -> null: jamas define identidad.
        assertNull(PerfumeNormalizer.gtin14("6291106066919"));
        // Vacio/basura -> null.
        assertNull(PerfumeNormalizer.gtin14("0"));
        assertNull(PerfumeNormalizer.gtin14(null));
    }

    @Test
    void hammingYTransposicionParaDeteccionDeTypos() {
        assertEquals(1, GtinCanonicalizer.hamming("06291106066919", "06291106066319"));
        assertTrue(GtinCanonicalizer.adjacentTransposition("12345678", "12435678"));
        assertFalse(GtinCanonicalizer.adjacentTransposition("12345678", "12345678"));
        assertFalse(GtinCanonicalizer.adjacentTransposition("12345678", "21436587"));
    }
}
