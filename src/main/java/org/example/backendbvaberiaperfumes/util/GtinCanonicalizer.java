package org.example.backendbvaberiaperfumes.util;

import java.math.BigDecimal;
import java.text.Normalizer;

/**
 * Canonicalizacion de codigos de barras (UPC/EAN/GTIN) a GTIN-14 validado.
 *
 * Problemas reales que resuelve (verificados en los Excel de proveedores):
 *  - Excel guarda el UPC como numero y se come el cero inicial (11 digitos).
 *  - Celdas de texto con espacios invisibles ( ) o asteriscos pegados.
 *  - Numeros grandes que llegan como Double en notacion cientifica.
 *  - Typos del proveedor: un digito cambiado crea un "producto fantasma";
 *    el checksum GS1 los detecta y el codigo queda en cuarentena (CHECKSUM_FAIL).
 *  - UPC-E (8 digitos comprimidos) y EAN-8.
 *
 * Politica: un codigo que NO valida checksum jamas define identidad de producto.
 * La fila se degrada a "sin GTIN" y pasa por el matching por nombre (L2).
 */
public final class GtinCanonicalizer {

    private GtinCanonicalizer() {}

    public enum Status {
        /** Codigo valido; canonical14 es la identidad. */
        OK,
        /** Celda vacia, solo ceros o sin digitos. */
        EMPTY,
        /** Cantidad de digitos que no corresponde a ningun formato GS1. */
        INVALID_LENGTH,
        /** Largo correcto pero el digito verificador no cuadra (typo probable). */
        CHECKSUM_FAIL,
        /** 8 digitos que validan como mas de una interpretacion (UPC-E y EAN-8). */
        AMBIGUOUS
    }

    public static final class GtinResult {
        /** GTIN-14 canonico (solo cuando status == OK). */
        public final String canonical14;
        /** Digitos crudos tras limpiar (para auditoria/cuarentena). */
        public final String rawDigits;
        public final Status status;

        private GtinResult(String canonical14, String rawDigits, Status status) {
            this.canonical14 = canonical14;
            this.rawDigits = rawDigits;
            this.status = status;
        }

        public boolean ok() { return status == Status.OK; }
    }

    /** Limpia, repara y valida un codigo de barras. Nunca lanza excepcion. */
    public static GtinResult canonicalize(Object raw) {
        if (raw == null) return new GtinResult(null, null, Status.EMPTY);

        String s;
        if (raw instanceof Number) {
            // Evita notacion cientifica de Double/Float en numeros grandes.
            s = new BigDecimal(raw.toString()).toBigInteger().toString();
        } else {
            s = raw.toString();
        }
        // NFKC: convierte  , espacios raros y digitos fullwidth a ASCII.
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        String digits = s.replaceAll("\\D", "");

        if (digits.isEmpty() || digits.matches("0+")) {
            return new GtinResult(null, digits.isEmpty() ? null : digits, Status.EMPTY);
        }

        switch (digits.length()) {
            case 12:
            case 13:
            case 14:
                if (checksumOk(digits)) return ok(digits);
                return new GtinResult(null, digits, Status.CHECKSUM_FAIL);
            case 11: {
                // UPC-A al que Excel le quito el cero inicial.
                String padded = "0" + digits;
                if (checksumOk(padded)) return ok(padded);
                return new GtinResult(null, digits, Status.CHECKSUM_FAIL);
            }
            case 8: {
                // Puede ser UPC-E comprimido o EAN-8: se prueban ambas interpretaciones.
                String upcA = expandUpcE(digits);
                boolean upcOk = upcA != null && checksumOk(upcA);
                boolean ean8Ok = checksumOk(digits);
                if (upcOk && ean8Ok) return new GtinResult(null, digits, Status.AMBIGUOUS);
                if (upcOk) return ok(upcA);
                if (ean8Ok) return ok(digits);
                return new GtinResult(null, digits, Status.CHECKSUM_FAIL);
            }
            default:
                return new GtinResult(null, digits, Status.INVALID_LENGTH);
        }
    }

    private static GtinResult ok(String digits) {
        return new GtinResult(pad14(digits), digits, Status.OK);
    }

    /** Zero-pad a 14: unifica EAN-13 con cero inicial y UPC-A automaticamente. */
    public static String pad14(String digits) {
        return String.format("%14s", digits).replace(' ', '0');
    }

    /**
     * Checksum GS1 mod-10 (vale para UPC-A/EAN-8/EAN-13/GTIN-14 tras zero-pad):
     * pesos 3,1,3,1... desde la izquierda sobre los primeros 13 digitos del codigo
     * padeado a 14; el resultado debe igualar el ultimo digito.
     */
    public static boolean checksumOk(String digits) {
        if (digits == null || digits.isEmpty() || !digits.matches("\\d+")) return false;
        String d = pad14(digits);
        int total = 0;
        for (int i = 0; i < 13; i++) {
            int n = d.charAt(i) - '0';
            total += (i % 2 == 0) ? n * 3 : n;
        }
        int check = (10 - total % 10) % 10;
        return check == (d.charAt(13) - '0');
    }

    /**
     * Expansion estandar UPC-E (8 digitos: sistema + 6 comprimidos + verificador) a UPC-A.
     * Solo sistemas 0 y 1 existen en UPC-E. Devuelve null si no es expandible.
     */
    static String expandUpcE(String e8) {
        if (e8 == null || e8.length() != 8) return null;
        char ns = e8.charAt(0);
        if (ns != '0' && ns != '1') return null;
        String body = e8.substring(1, 7);
        char check = e8.charAt(7);
        char last = body.charAt(5);
        String middle;
        switch (last) {
            case '0':
            case '1':
            case '2':
                middle = body.substring(0, 2) + last + "0000" + body.substring(2, 5);
                break;
            case '3':
                middle = body.substring(0, 3) + "00000" + body.substring(3, 5);
                break;
            case '4':
                middle = body.substring(0, 4) + "00000" + body.charAt(4);
                break;
            default: // 5..9
                middle = body.substring(0, 5) + "0000" + last;
                break;
        }
        return ns + middle + check;
    }

    /** Distancia de Hamming entre dos codigos del mismo largo; -1 si no comparables. */
    public static int hamming(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return -1;
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) d++;
        }
        return d;
    }

    /** True si b se obtiene de a intercambiando dos digitos adyacentes (transposicion). */
    public static boolean adjacentTransposition(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int first = -1;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) { first = i; break; }
        }
        if (first < 0 || first + 1 >= a.length()) return false;
        if (a.charAt(first) != b.charAt(first + 1) || a.charAt(first + 1) != b.charAt(first)) return false;
        // El resto debe ser identico.
        for (int i = first + 2; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) return false;
        }
        return true;
    }
}
