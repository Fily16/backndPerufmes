package org.example.backendbvaberiaperfumes.service.matching;

import java.util.Set;

/**
 * Huella estructurada de un perfume para el matching por nombre (L2).
 * Se extrae con FingerprintExtractor tanto de una fila de proveedor como
 * de un Product del catalogo, y se compara con MatchScorer.
 */
public class ProductFingerprint {

    /** Tokens de la marca, normalizados (minusculas, sin acentos). */
    public Set<String> brandTokens;

    /**
     * Tokens del NOMBRE del perfume sin marca, sin descriptores (genero, concentracion,
     * presentacion, ruido) y sin los numeros del tamano. Es lo que distingue a un
     * flanker de otro: "yara" vs "yara candy" difieren en {candy}.
     */
    public Set<String> coreTokens;

    /** Mililitros (null si no se pudo determinar). */
    public Integer ml;

    /** extrait | edp | edt | edc | parfum | null. */
    public String concentration;

    /** men | women | unisex | null. */
    public String gender;

    /** regular | tester | set | oil | deo | mini. */
    public String presentation;

    @Override
    public String toString() {
        return "FP{brand=" + brandTokens + ", core=" + coreTokens + ", ml=" + ml
                + ", conc=" + concentration + ", gender=" + gender + ", pres=" + presentation + "}";
    }
}
