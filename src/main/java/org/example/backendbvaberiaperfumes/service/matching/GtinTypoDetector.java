package org.example.backendbvaberiaperfumes.service.matching;

import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Busca productos del catalogo cuyo GTIN valido este a UN error de tipeo del
 * codigo invalido recibido (distancia Hamming 1 o transposicion adyacente).
 *
 * Caso real: FragranceSense trae 6291106066919 (checksum invalido) para
 * "Lattafa Ser Al Khulood Brown"; Zimaxx tiene el valido 6291106066319.
 * Sin esta deteccion, el typo crearia un producto fantasma duplicado.
 */
public final class GtinTypoDetector {

    private GtinTypoDetector() {}

    /** Candidatos del catalogo a distancia de typo del codigo invalido. */
    public static List<Product> suggest(String rawDigits, Map<String, Product> gtinIndex) {
        List<Product> hits = new ArrayList<>();
        if (rawDigits == null || rawDigits.length() < 11 || rawDigits.length() > 14) return hits;
        String padded = GtinCanonicalizer.pad14(rawDigits);
        for (Map.Entry<String, Product> e : gtinIndex.entrySet()) {
            String candidate = e.getKey();
            if (GtinCanonicalizer.hamming(padded, candidate) == 1
                    || GtinCanonicalizer.adjacentTransposition(padded, candidate)) {
                hits.add(e.getValue());
            }
        }
        return hits;
    }
}
