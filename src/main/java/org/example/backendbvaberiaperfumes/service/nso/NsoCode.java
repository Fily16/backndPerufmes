package org.example.backendbvaberiaperfumes.service.nso;

import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codigos de Notificacion Sanitaria Obligatoria (NSO) de cosmeticos (puro, sin Spring ni BD).
 *
 * Formato canonico (100% del catalogo real): NSOC + 5 o 6 digitos + "-" + anio (2 digitos) + pais CAN.
 *   NSOC70523-25PE, NSOC780229-25PE (6 digitos), NSOC06935-11PE (el cero inicial ES parte del codigo).
 * Paises CAN: PE (Peru), CO (Colombia), BO (Bolivia), EC (Ecuador).
 */
public final class NsoCode {

    private NsoCode() {}

    public static final Set<String> COUNTRIES = Set.of("PE", "CO", "BO", "EC");

    /** Un NSO vence a los 7 anios: pasado eso solo se AVISA ("vigente?"), no se bloquea. */
    public static final int VALID_YEARS = 7;

    /** Formato canonico estricto. */
    public static final Pattern CANONICAL = Pattern.compile("^NSOC\\d{5,6}-\\d{2}(PE|CO|BO|EC)$");

    /** Forma tolerante que acepta canonicalize() tras quitar espacios: prefijo opcional/incompleto (N?SOC?). */
    private static final Pattern TOLERANT = Pattern.compile("^(?:N?SOC?)?(\\d{5,6})-(\\d{2})(PE|CO|BO|EC)$");

    /** Extractor laxo para texto libre ("Reg. N SOC 81196-26 PE"); luego se valida el formato. */
    private static final Pattern LOOSE = Pattern.compile(
            "\\b(N?\\s?SOC?\\s?\\d{3,7}\\s?-\\s?\\d{2}\\s?[A-Z]{2})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Lleva un codigo escrito a mano a su forma canonica: mayusculas, sin espacios, guiones raros -> "-",
     * prefijo NSOC completado. Devuelve null si no es un codigo valido (nunca inventa digitos: los ceros
     * a la izquierda se conservan tal cual).
     *   "nsoc 70523 - 25 pe" -> "NSOC70523-25PE";  "NSOC7052-25PE" -> null.
     */
    public static String canonicalize(String raw) {
        if (raw == null) return null;
        String s = PerfumeNormalizer.stripAccents(raw).toUpperCase(Locale.ROOT)
                .replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                .replaceAll("\\s+", "");
        if (s.isEmpty()) return null;
        Matcher m = TOLERANT.matcher(s);
        if (!m.matches()) return null;
        return "NSOC" + m.group(1) + "-" + m.group(2) + m.group(3);
    }

    /** True solo si el texto YA esta en forma canonica exacta (para validar lo guardado). */
    public static boolean isValid(String code) {
        return code != null && CANONICAL.matcher(code).matches();
    }

    /**
     * Extrae todos los codigos validos de un texto libre, canonicos, sin repetir y en orden de aparicion.
     * Lo que parece codigo pero no valida (ej. 4 digitos) se descarta.
     */
    public static List<String> extractLoose(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return new ArrayList<>(out);
        Matcher m = LOOSE.matcher(PerfumeNormalizer.stripAccents(text));
        while (m.find()) {
            String c = canonicalize(m.group(1));
            if (c != null) out.add(c);
        }
        return new ArrayList<>(out);
    }

    /** Primer codigo valido del texto, o null. */
    public static String extractFirst(String text) {
        List<String> all = extractLoose(text);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Anio de la NSO desde el codigo (pivot sobre el anio actual): yy > (anioActual % 100) + 1 -> 1900 + yy,
     * si no 2000 + yy. Con anioActual 2026: "..-97PE" -> 1997, "..-26PE" -> 2026, "..-27PE" -> 2027.
     * Null si el codigo no es valido. (La columna anio_nso del CSV esta rota: se ignora.)
     */
    public static Integer year(String code, int currentYear) {
        String c = canonicalize(code);
        if (c == null) return null;
        int dash = c.indexOf('-');
        int yy = Integer.parseInt(c.substring(dash + 1, dash + 3));
        return yy > (currentYear % 100) + 1 ? 1900 + yy : 2000 + yy;
    }

    /** Igual que year(code, anioActual) usando el anio del reloj del sistema. */
    public static Integer year(String code) {
        return year(code, Year.now().getValue());
    }

    /** Pais CAN del codigo (PE/CO/BO/EC) o null si no es valido. */
    public static String country(String code) {
        String c = canonicalize(code);
        if (c == null) return null;
        return c.substring(c.length() - 2);
    }

    /** True si el codigo es de otro pais de la CAN (no PE). False si es PE o invalido. */
    public static boolean isOtherCanCountry(String code) {
        String country = country(code);
        return country != null && !"PE".equals(country);
    }

    /** Aviso "vigente?": nsoYear + 7 < anio actual. Null -> false (no se puede afirmar nada). */
    public static boolean possiblyExpired(Integer nsoYear, int currentYear) {
        return nsoYear != null && nsoYear + VALID_YEARS < currentYear;
    }
}
