package org.example.backendbvaberiaperfumes;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.example.backendbvaberiaperfumes.service.nso.NsoCatalogParser;
import org.example.backendbvaberiaperfumes.service.nso.NsoMatcher;
import org.example.backendbvaberiaperfumes.service.nso.NsoMatcher.Evidence;
import org.example.backendbvaberiaperfumes.service.nso.NsoMatcher.Result;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CALIBRACION del reconocimiento NSO con los archivos REALES (catalogo + listas de Zimaxx, Oasis y
 * FragranceSense). Se SALTA si falta alguno (no estan en el repo: son inteligencia comercial).
 *
 * Rutas (system property o variable de entorno, con defaults de la maquina de la duena):
 *   nso.catalog   / NSO_CATALOG
 *   nso.zimaxx    / NSO_ZIMAXX
 *   nso.oasis     / NSO_OASIS
 *   nso.fragsense / NSO_FRAGSENSE  (archivo, o carpeta donde buscar FRAGSENSE*.xlsx)
 *
 * Escribe target/nso-auto-pairs.csv (TODOS los automaticos por nombre, para revisarlos a mano),
 * target/nso-review-pairs.csv y target/nso-marca-pairs.csv (MARCA_CON_NSO y SIN_NSO). Afirma que los falsos positivos conocidos
 * nunca quedan CON_NSO.
 */
class NsoCalibrationRealFilesTest {

    private static final String DEF_CATALOG = "C:/Users/Fily/Negocios/PERFUMES/analisis de competencia/NSO-perfumes-FINAL.xlsx";
    private static final String DEF_ZIMAXX = "C:/Users/Fily/Downloads/US Wholesale - 2K - Available. (6).xlsx";
    private static final String DEF_OASIS = "C:/Users/Fily/Downloads/Price list - Oasis Perfumes - Liquidación (7).xlsx";
    private static final String DEF_FRAGSENSE = "C:/Users/Fily/Universidad/Programas/backendbvaberiaperfumes/provedores";

    /** Fila de proveedor ya convertida en evidencia. */
    record Row2(String list, String brand, String text, Evidence evidence) {}

    @Test
    void calibraConArchivosReales() throws Exception {
        File catalog = new File(path("nso.catalog", "NSO_CATALOG", DEF_CATALOG));
        File zimaxx = new File(path("nso.zimaxx", "NSO_ZIMAXX", DEF_ZIMAXX));
        File oasis = new File(path("nso.oasis", "NSO_OASIS", DEF_OASIS));
        File fragsense = findFragsense(new File(path("nso.fragsense", "NSO_FRAGSENSE", DEF_FRAGSENSE)));
        Assumptions.assumeTrue(catalog.isFile(), "falta el catalogo NSO: " + catalog);
        Assumptions.assumeTrue(zimaxx.isFile(), "falta la lista Zimaxx: " + zimaxx);
        Assumptions.assumeTrue(oasis.isFile(), "falta la lista Oasis: " + oasis);
        Assumptions.assumeTrue(fragsense != null && fragsense.isFile(), "falta la lista FragranceSense");

        NsoCatalogParser.ParsedCatalog parsed = new NsoCatalogParser()
                .parse(catalog.getName(), Files.readAllBytes(catalog.toPath()), 2026);
        List<NsoRecord> records = parsed.records;
        System.out.println("CATALOGO registros=" + records.size() + " links investigacion=" + parsed.researchLinks.size());

        List<Row2> rows = new ArrayList<>();
        rows.addAll(readZimaxx(zimaxx));
        rows.addAll(readOasis(oasis));
        rows.addAll(readFragsense(fragsense));
        List<Evidence> all = new ArrayList<>();
        for (Row2 r : rows) all.add(r.evidence());

        // Pasada A: SIN investigacion (lo que decide el nombre por si solo; es lo que se revisa a mano).
        NsoMatcher pure = new NsoMatcher(NsoMatcher.Index.builder()
                .records(records).siblings(all).currentYear(2026).build());
        // Pasada B: CON los alias de investigacion (lo que veria la duena al subir el xlsx completo).
        NsoMatcher withResearch = new NsoMatcher(NsoMatcher.Index.builder()
                .records(records).aliases(parsed.researchAliases()).siblings(all).currentYear(2026).build());

        Map<String, Map<String, Integer>> countsA = new TreeMap<>();
        Map<String, Map<String, Integer>> countsB = new TreeMap<>();
        List<String> falsePositives = new ArrayList<>();
        File target = new File("target");
        target.mkdirs();
        try (PrintWriter auto = csv(new File(target, "nso-auto-pairs.csv"));
             PrintWriter review = csv(new File(target, "nso-review-pairs.csv"));
             PrintWriter marca = csv(new File(target, "nso-marca-pairs.csv"))) {
            auto.println("lista,marca,texto proveedor,codigo,nombre declarado,matchedBy,motivos");
            review.println("lista,marca,texto proveedor,candidatos (codigo · nombre declarado · score),motivos");
            marca.println("estado,lista,marca,brandKey,texto proveedor,marca sugerida");
            for (Row2 row : rows) {
                Result a = pure.resolve(row.evidence());
                Result b = withResearch.resolve(row.evidence());
                countsA.computeIfAbsent(row.list(), k -> new TreeMap<>()).merge(a.status, 1, Integer::sum);
                countsB.computeIfAbsent(row.list(), k -> new TreeMap<>()).merge(b.status, 1, Integer::sum);
                for (Result r : List.of(a, b)) {
                    String fp = knownFalsePositive(row.text(), r);
                    if (fp != null) falsePositives.add(row.list() + " | " + row.text() + " -> " + r.nsoCode + " : " + fp);
                }
                if (a.isConNso() && !b.isConNso()) {
                    List<String> cs = new ArrayList<>();
                    for (NsoMatcher.Candidate c : b.candidates) {
                        cs.add(c.code + " · " + c.origin + " · " + pure.index().record(c.code).getDeclaredName());
                    }
                    System.out.println("INVESTIGACION DISTINTA | " + row.list() + " | " + row.text() + " | nombre: "
                            + a.nsoCode + " " + pure.index().record(a.nsoCode).getDeclaredName() + " | " + cs);
                }
                if (a.isConNso()) {
                    NsoRecord rec = pure.index().record(a.nsoCode);
                    auto.println(String.join(",", q(row.list()), q(row.brand()), q(row.text()), q(a.nsoCode),
                            q(rec.getDeclaredName()), q(a.matchedBy), q(String.join(" | ", a.reasons))));
                } else if (ProductNso.STATUS_EN_REVISION.equals(a.status)) {
                    List<String> cs = new ArrayList<>();
                    for (NsoMatcher.Candidate c : a.candidates) {
                        cs.add(c.code + " · " + pure.index().record(c.code).getDeclaredName() + " · " + c.score);
                    }
                    review.println(String.join(",", q(row.list()), q(row.brand()), q(row.text()),
                            q(String.join(" || ", cs)), q(String.join(" | ", a.reasons))));
                } else if (ProductNso.STATUS_MARCA_CON_NSO.equals(a.status)) {
                    marca.println(String.join(",", q("MARCA"), q(row.list()), q(row.brand()), q(a.brandKey), q(row.text()), q("")));
                } else {
                    marca.println(String.join(",", q("SIN"), q(row.list()), q(row.brand()), q(a.brandKey), q(row.text()),
                            q(a.suggestedBrand)));
                }
            }
        }
        System.out.println("CONTEOS sin investigacion: " + countsA);
        System.out.println("CONTEOS con investigacion: " + countsB);
        falsePositives.forEach(s -> System.out.println("FALSO POSITIVO " + s));
        assertTrue(falsePositives.isEmpty(), "falsos positivos conocidos: " + falsePositives);
        assertFalse(countsA.isEmpty());
    }

    /** Pares que la investigacion dio por buenos y NO lo son (plan): nunca pueden quedar CON_NSO. */
    private static String knownFalsePositive(String text, Result r) {
        if (!r.isConNso() || r.nsoCode == null) return null;
        String t = PerfumeNormalizer.stripAccents(text).toLowerCase(Locale.ROOT);
        return switch (r.nsoCode) {
            case "NSOC76211-25PE" -> t.contains("oud") ? null : "Club De Nuit sin OUD contra CLUB DE NUIT OUD";
            case "NSOC52223-22PE" -> t.matches(".*\\bedt\\b.*") ? "The Icon EDT contra AB THE ICON EDP" : null;
            case "NSOC44933-20PE" -> t.matches(".*\\bedp\\b.*") ? "The Icon EDP contra AB THE ICON EDT" : null;
            case "NSOC49004-21PE" -> t.contains("queen") ? "Queen Of Seduction contra KING OF SEDUCTION" : null;
            case "NSOC81196-26PE" -> t.matches(".*\\b(unisex|u|men|m)\\b.*") ? "9AM no-mujer contra 9AM POUR FEMME" : null;
            case "NSOC74343-25PE", "NSOC76672-25PE" -> t.contains("candy") ? null : "Yara sin Candy contra YARA CANDY";
            case "NSOC78874-26PE" -> t.matches(".*\\bedp\\b.*") ? "Supremacy Gala EDP contra EXTRACTO" : null;
            case "NSOC72991-25PE" -> "MANDARIN SK (cortado) nunca automatico";
            default -> null;
        };
    }

    // ===================== lectura de las listas =====================

    private static List<Row2> readZimaxx(File f) throws Exception {
        List<Row2> out = new ArrayList<>();
        try (FileInputStream is = new FileInputStream(f); Workbook wb = WorkbookFactory.create(is)) {
            Sheet sh = wb.getSheet("Price List") != null ? wb.getSheet("Price List") : wb.getSheetAt(0);
            Map<String, Integer> h = null;
            int start = 0;
            for (int r = 0; r <= Math.min(20, sh.getLastRowNum()); r++) {
                Map<String, Integer> m = headers(sh.getRow(r));
                if (m.containsKey("upc") && m.containsKey("title product")) {
                    h = m;
                    start = r + 1;
                    break;
                }
            }
            assertNotNull(h, "header Zimaxx");
            for (int r = start; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                String title = str(row, h.get("title product"));
                if (title == null || title.isBlank()) continue;
                String brand = str(row, h.get("brand"));
                String upc = rawId(row, h.get("upc"));
                String sku = str(row, h.get("sku"));
                Evidence e = Evidence.of((long) (100000 + r), brand, PerfumeNormalizer.cleanName(title))
                        .offer("Zimaxx", sku, title, upc).gtin(upc).ml(PerfumeNormalizer.mlFromOz(title))
                        .forma(PerfumeNormalizer.detectForma(title));
                out.add(new Row2("Zimaxx", brand, title, e));
            }
        }
        System.out.println("Zimaxx filas=" + out.size());
        return out;
    }

    private static List<Row2> readOasis(File f) throws Exception {
        List<Row2> out = new ArrayList<>();
        try (FileInputStream is = new FileInputStream(f); Workbook wb = WorkbookFactory.create(is)) {
            Sheet sh = wb.getSheet("Hoja 1") != null ? wb.getSheet("Hoja 1") : wb.getSheetAt(0);
            Map<String, Integer> h = null;
            int start = 0;
            for (int r = 0; r <= Math.min(20, sh.getLastRowNum()); r++) {
                Map<String, Integer> m = headers(sh.getRow(r));
                if (m.containsKey("sku") && m.containsKey("name") && m.containsKey("brand")) {
                    h = m;
                    start = r + 1;
                    break;
                }
            }
            assertNotNull(h, "header Oasis");
            for (int r = start; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                String name = str(row, h.get("name"));
                if (name == null || name.isBlank()) continue;
                String brand = str(row, h.get("brand"));
                String sku = str(row, h.get("sku"));
                String b = brand != null && !brand.isBlank() ? brand : name.split("\\s+")[0];
                Evidence e = Evidence.of((long) (200000 + r), b,
                                PerfumeNormalizer.cleanName(PerfumeNormalizer.stripBrandPrefix(b, name)))
                        .offer("Oasis", sku, name, null).forma(PerfumeNormalizer.detectForma(name));
                out.add(new Row2("Oasis", b, name, e));
            }
        }
        System.out.println("Oasis filas=" + out.size());
        return out;
    }

    private static List<Row2> readFragsense(File f) throws Exception {
        List<Row2> out = new ArrayList<>();
        try (FileInputStream is = new FileInputStream(f); Workbook wb = WorkbookFactory.create(is)) {
            Sheet sh = wb.getSheetAt(0);
            Map<String, Integer> h = null;
            int start = 0;
            for (int r = 0; r <= Math.min(20, sh.getLastRowNum()); r++) {
                Map<String, Integer> m = headers(sh.getRow(r));
                if (m.containsKey("description") && m.containsKey("upc")) {
                    h = m;
                    start = r + 1;
                    break;
                }
            }
            assertNotNull(h, "header FragranceSense");
            for (int r = start; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                String desc = str(row, h.get("description"));
                if (desc == null || desc.isBlank()) continue;
                String upc = rawId(row, h.get("upc"));
                // Igual que GenericSupplierParser: sin columna marca -> marca = primera palabra.
                String brand = desc.trim().split("\\s+")[0];
                Evidence e = Evidence.of((long) (300000 + r), brand,
                                PerfumeNormalizer.cleanName(PerfumeNormalizer.stripBrandPrefix(brand, desc)))
                        .offer("FragranceSense", upc, desc, upc).gtin(upc).forma(PerfumeNormalizer.detectForma(desc));
                out.add(new Row2("FragranceSense", brand, desc, e));
            }
        }
        System.out.println("FragranceSense filas=" + out.size());
        return out;
    }

    // ===================== utilidades =====================

    private static String path(String prop, String env, String def) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? def : v;
    }

    private static File findFragsense(File f) {
        if (f.isFile()) return f;
        if (!f.isDirectory()) return null;
        File[] fs = f.listFiles((dir, name) -> name.toUpperCase(Locale.ROOT).startsWith("FRAGSENSE")
                && name.toLowerCase(Locale.ROOT).endsWith(".xlsx"));
        if (fs == null || fs.length == 0) return null;
        java.util.Arrays.sort(fs);
        return fs[0];
    }

    private static Map<String, Integer> headers(Row row) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (row == null) return m;
        for (int i = Math.max(0, row.getFirstCellNum()); i < row.getLastCellNum(); i++) {
            String s = str(row, i);
            if (s != null && !s.isBlank()) m.putIfAbsent(s.trim().toLowerCase(Locale.ROOT), i);
        }
        return m;
    }

    private static String str(Row row, Integer col) {
        if (row == null || col == null || col < 0) return null;
        Cell c = row.getCell(col);
        if (c == null) return null;
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (t) {
            case STRING -> c.getStringCellValue().replace((char) 0xA0, ' ').trim();
            case NUMERIC -> new BigDecimal(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> null;
        };
    }

    private static String rawId(Row row, Integer col) {
        if (row == null || col == null || col < 0) return null;
        Cell c = row.getCell(col);
        if (c == null) return null;
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (t == CellType.NUMERIC) return new BigDecimal(c.getNumericCellValue()).toBigInteger().toString();
        return str(row, col);
    }

    private static PrintWriter csv(File f) throws Exception {
        PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8);
        w.print('\uFEFF');
        return w;
    }

    private static String q(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
