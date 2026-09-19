package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.service.nso.NsoCode;
import org.example.backendbvaberiaperfumes.service.nso.NsoKeys;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Codigos NSO y claves normalizadas (puro, sin Spring). */
class NsoCodeTest {

    @Test
    void canonicalizaEspaciosYMinusculas() {
        assertEquals("NSOC70523-25PE", NsoCode.canonicalize("nsoc 70523 - 25 pe"));
        assertEquals("NSOC70523-25PE", NsoCode.canonicalize("  NSOC70523-25PE "));
        assertEquals("NSOC70523-25PE", NsoCode.canonicalize("NSOC70523–25PE"), "guion largo");
        assertEquals("NSOC70523-25PE", NsoCode.canonicalize("SOC 70523-25PE"), "prefijo incompleto");
    }

    @Test
    void validaFormato() {
        assertTrue(NsoCode.isValid("NSOC780229-25PE"), "6 digitos es valido");
        assertNotNull(NsoCode.canonicalize("NSOC780229-25PE"));
        assertFalse(NsoCode.isValid("NSOC7052-25PE"), "4 digitos no");
        assertNull(NsoCode.canonicalize("NSOC7052-25PE"));
        assertNull(NsoCode.canonicalize("NSOC7052291-25PE"), "7 digitos no");
        assertNull(NsoCode.canonicalize("NSOC70523-25US"), "pais fuera de la CAN");
        assertNull(NsoCode.canonicalize(null));
        assertNull(NsoCode.canonicalize("   "));
        assertFalse(NsoCode.isValid("nsoc70523-25pe"), "isValid exige la forma canonica exacta");
    }

    @Test
    void conservaCerosALaIzquierda() {
        assertEquals("NSOC06935-11PE", NsoCode.canonicalize("nsoc06935-11pe"));
        assertTrue(NsoCode.isValid("NSOC06935-11PE"));
        assertNotEquals(NsoCode.canonicalize("NSOC6935-11PE"), "NSOC06935-11PE");
    }

    @Test
    void anioConPivot() {
        assertEquals(1997, NsoCode.year("NSOC12345-97PE", 2026));
        assertEquals(2026, NsoCode.year("NSOC12345-26PE", 2026));
        assertEquals(2027, NsoCode.year("NSOC12345-27PE", 2026), "anio actual + 1 sigue siendo 20xx");
        assertEquals(1928, NsoCode.year("NSOC12345-28PE", 2026));
        assertEquals(2011, NsoCode.year("NSOC06935-11PE", 2026));
        assertNull(NsoCode.year("basura", 2026));
    }

    @Test
    void pais() {
        assertEquals("CO", NsoCode.country("NSOC38563-25CO"));
        assertEquals("PE", NsoCode.country("NSOC70523-25PE"));
        assertEquals("BO", NsoCode.country("nsoc 12345-20 bo"));
        assertNull(NsoCode.country("NSOC7052-25PE"));
        assertTrue(NsoCode.isOtherCanCountry("NSOC38563-25CO"));
        assertFalse(NsoCode.isOtherCanCountry("NSOC70523-25PE"));
    }

    @Test
    void extraccionLaxa() {
        assertEquals(List.of("NSOC81196-26PE"), NsoCode.extractLoose("Reg. N SOC 81196-26 PE"));
        assertEquals(List.of("NSOC70523-25PE", "NSOC38563-25CO"),
                NsoCode.extractLoose("Lote: NSOC70523-25PE / tambien nsoc 38563 - 25 co y otra vez NSOC70523-25PE"));
        assertTrue(NsoCode.extractLoose("NSOC7052-25PE codigo corto").isEmpty(), "lo que no valida se descarta");
        assertTrue(NsoCode.extractLoose(null).isEmpty());
        assertEquals("NSOC81196-26PE", NsoCode.extractFirst("registro NSOC81196-26PE"));
        assertNull(NsoCode.extractFirst("sin codigo"));
    }

    @Test
    void posibleVencido() {
        assertTrue(NsoCode.possiblyExpired(2018, 2026), "2018 + 7 = 2025 < 2026");
        assertFalse(NsoCode.possiblyExpired(2019, 2026), "2019 + 7 = 2026 no es < 2026");
        assertFalse(NsoCode.possiblyExpired(null, 2026));
    }

    @Test
    void clavesDeProveedorMarcaYSku() {
        assertEquals("oasis", NsoKeys.supplierKey("Oasis"));
        assertEquals(NsoKeys.supplierKey("Oasis"), NsoKeys.supplierKey("Oasis Perfumes"));
        assertEquals(NsoKeys.supplierKey("Zimaxx"), NsoKeys.supplierKey("ZIMAXX INC"));
        assertEquals("fragrancesense", NsoKeys.supplierKey("FragranceSense"));
        assertEquals("oasis", NsoKeys.supplierKey("Perfumes Oasis"), "salta palabras genericas al inicio");
        assertEquals("", NsoKeys.supplierKey(null));

        assertEquals("oasis|PERF-AFNA-26", NsoKeys.skuKey("Oasis Perfumes", " perf-afna-26 "));
        assertNull(NsoKeys.skuKey("Oasis", "  "));
        assertNull(NsoKeys.skuKey(null, "PERF-AFNA-26"));

        assertEquals("dolce and gabbana", NsoKeys.brandKey("DOLCE & GABBANA"));
        assertEquals("paco rabanne", NsoKeys.brandKey("  Pacó   Rabanne "));
        assertEquals(NsoKeys.brandKey("antonio banderas"), NsoKeys.brandKey(NsoKeys.brandKey("ANTONIO BANDERAS")));
        assertEquals("montblanc", NsoKeys.brandCompact("MONT BLANC"));

        assertEquals("06290171000976", NsoKeys.gtinKey("6290171000976"));
        assertNull(NsoKeys.gtinKey("6290171000977"), "checksum invalido no es clave");

        assertEquals("afnan|gala supremacy|parfum|women|perfume",
                NsoKeys.nameKey("Afnan", List.of("supremacy", "gala"), "parfum", "women", "perfume"));
    }

    @Test
    void fechas() {
        assertEquals("05/07/2026", NsoKeys.normalizeDate("5/7/2026"));
        assertEquals("16/06/2026", NsoKeys.normalizeDate("2026-06-16"));
        assertNull(NsoKeys.normalizeDate("31/02/2026"));
        assertNull(NsoKeys.normalizeDate("ayer"));
        assertTrue(NsoKeys.dateSortKey("16/06/2026") > NsoKeys.dateSortKey("20/07/2025"));
        assertEquals(0, NsoKeys.dateSortKey(null));
    }
}
