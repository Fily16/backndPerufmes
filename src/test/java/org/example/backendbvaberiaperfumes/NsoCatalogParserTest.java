package org.example.backendbvaberiaperfumes;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.service.nso.NsoCatalogParser;
import org.example.backendbvaberiaperfumes.service.nso.NsoCatalogParser.ParsedCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Lector del catalogo NSO (puro, sin Spring ni BD). */
class NsoCatalogParserTest {

    private static final int YEAR = 2026;
    private final NsoCatalogParser parser = new NsoCatalogParser();

    // ============================ CSV ============================

    private static final String CSV_HEADER = "nso,categoria,marca,producto,ean,titular,ruc,origen,series,kg,fob,usd_kg,ultima,anio_nso";

    private static byte[] csv(String... lines) {
        return ("\uFEFF" + String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sampleCsv() {
        return csv(
                CSV_HEADER,
                "NSOC81196-26PE,Arabe,AFNAN,AGUA DE PERFUME - 9AM POUR FEMME,6290171002345,TITULAR DEMO DOS S.A.C,20999999902,United Arab Emirates,1,10.0,250.0,25.0,01/07/2026,2026",
                "NSOC62224-23PE,Disenador,CHRISTIAN DIOR,\"DIORIVIERA EAU DE PARFUM SOFISTIC SAMPLE 1,20 ML\",,TITULAR DEMO CUATRO S.A,20999999904,France,3,5.0,400.0,80.0,02/07/2026,2023",
                "nsoc 06935-97pe,Disenador,\"MAISON \"\"X\"\" PARIS\",COLONIA,6290171000977,TITULAR DEMO,20100000001,France,,,,,,2097",
                "NSOC7052-25PE,Arabe,LATTAFA,YARA,,,,,,,,,,",
                "NSOC72246-25PE,Arabe,ARMAF,CLUB DE NUIT,sobra",
                "",
                "NSOC81196-26PE,Arabe,AFNAN,DUPLICADO,,,,,,,,,,",
                ",Arabe,AFNAN,SIN CODIGO,,,,,,,,,,",
                "NSOC38563-25CO,Disenador,COACH,,,,,,,,,,,"
        );
    }

    /** CSV con celdas mas largas que las columnas de nso_records (RUC 20, tipo/categoria 40, origen 80, ...). */
    static byte[] csvConCeldasLargas() {
        String larga = "X".repeat(120);
        return csv(
                CSV_HEADER,
                // RUC de texto libre (25 caracteres) + categoria/origen/titular largos: el codigo se carga igual.
                "NSOC91101-25PE," + "Categoria " + larga + ",VELMORA,AGUA DE PERFUME-MOONLIGHT ROSE,,"
                        + "IMPORTADORA " + "Y".repeat(400) + ",20601234567 / 20509876543,Pais " + larga + ",,,,,,",
                // RUC largo sin ningun numero de 11 digitos: se carga sin RUC.
                "NSOC91102-25PE,Arabe,VELMORA,AGUA DE TOCADOR-AMBER NIGHT,,IMPORTADORA DEMO SAC,EN TRAMITE ANTE SUNAT (VER ANEXO),,,,,,,",
                // Marca y nombre declarado son identidad: si no caben, la fila es invalida con motivo (no se recorta).
                "NSOC91103-25PE,Arabe," + "M".repeat(151) + ",AGUA DE PERFUME-SILVER STORM,,,,,,,,,,",
                "NSOC91104-25PE,Arabe,VELMORA," + "N".repeat(501) + ",,,,,,,,,,"
        );
    }

    @Test
    void celdasMasLargasQueLaColumnaNoTumbanLaCarga() {
        ParsedCatalog pc = parser.parse("NSO-largos.csv", csvConCeldasLargas(), YEAR);
        Map<String, NsoRecord> byCode = pc.records.stream().collect(Collectors.toMap(NsoRecord::getCode, Function.identity()));
        assertEquals(Set.of("NSOC91101-25PE", "NSOC91102-25PE"), byCode.keySet());

        NsoRecord moon = byCode.get("NSOC91101-25PE");
        assertEquals("20601234567", moon.getRuc(), "del texto libre queda el primer RUC de 11 digitos");
        assertEquals(40, moon.getCategoria().length());
        assertEquals(40, moon.getTipo().length());
        assertEquals(80, moon.getOrigen().length());
        assertEquals(300, moon.getTitular().length());
        assertNull(byCode.get("NSOC91102-25PE").getRuc(), "sin un RUC reconocible se carga sin RUC");

        assertEquals(2, pc.invalidRows.size(), pc.invalidRows.stream().map(r -> r.reason).toList().toString());
        assertTrue(pc.invalidRows.get(0).reason.contains("La marca del código NSOC91103-25PE es demasiado larga"));
        assertTrue(pc.invalidRows.get(1).reason.contains("El producto declarado del código NSOC91104-25PE es demasiado largo"));
        for (NsoCatalogParser.InvalidRow r : pc.invalidRows) assertTrue(r.raw.length() <= 300);
    }

    @Test
    void csvConBomCrlfComillasYFilasInvalidas() {
        ParsedCatalog pc = parser.parse("NSO_perfumes_Peru.csv", sampleCsv(), YEAR);

        assertEquals("CSV", pc.fileType);
        assertTrue(pc.researchLinks.isEmpty());
        assertEquals(3, pc.records.size(), "3 validas");
        Map<String, NsoRecord> byCode = pc.records.stream().collect(Collectors.toMap(NsoRecord::getCode, Function.identity()));

        NsoRecord afnan = byCode.get("NSOC81196-26PE");
        assertNotNull(afnan, "la BOM no debe ensuciar el primer encabezado");
        assertEquals("AFNAN", afnan.getBrand());
        assertEquals("afnan", afnan.getBrandKey());
        assertEquals("AGUA DE PERFUME - 9AM POUR FEMME", afnan.getDeclaredName());
        assertEquals("06290171002345", afnan.getEan(), "EAN valido -> GTIN-14");
        assertEquals("20999999902", afnan.getRuc());
        assertEquals("Arabe", afnan.getCategoria());
        assertEquals("Arabe", afnan.getTipo());
        assertEquals("United Arab Emirates", afnan.getOrigen());
        assertEquals(1, afnan.getSeries());
        assertEquals(10.0, afnan.getKg());
        assertEquals(250.0, afnan.getFobUsd());
        assertEquals(25.0, afnan.getUsdKg());
        assertEquals("01/07/2026", afnan.getLastImportDate());
        assertEquals(2026, afnan.getNsoYear());
        assertEquals("PE", afnan.getCountry());
        assertEquals(NsoRecord.SOURCE_CSV, afnan.getSource());
        assertTrue(afnan.getActive());
        assertTrue(afnan.getInLastUpload());
        assertTrue(afnan.isNew(), "recien leido: listo para insertar sin SELECT");

        NsoRecord dior = byCode.get("NSOC62224-23PE");
        assertEquals("DIORIVIERA EAU DE PARFUM SOFISTIC SAMPLE 1,20 ML", dior.getDeclaredName(), "coma dentro de comillas");
        assertNull(dior.getEan());

        NsoRecord maison = byCode.get("NSOC06935-97PE");
        assertNotNull(maison, "codigo con minusculas y espacios se canonicaliza y conserva el cero");
        assertEquals("MAISON \"X\" PARIS", maison.getBrand(), "comillas escapadas");
        assertNull(maison.getEan(), "EAN con checksum invalido no se guarda");
        assertEquals(1997, maison.getNsoYear(), "anio desde el codigo, ignora anio_nso");
        assertNull(maison.getKg());

        List<NsoCatalogParser.InvalidRow> inv = pc.invalidRows;
        assertEquals(5, inv.size(), inv.stream().map(r -> r.row + ":" + r.reason).toList().toString());
        Map<Integer, String> reasonByRow = inv.stream().collect(Collectors.toMap(r -> r.row, r -> r.reason));
        assertTrue(reasonByRow.get(5).contains("formato inválido"), reasonByRow.get(5));
        assertTrue(reasonByRow.get(6).contains("columnas"), reasonByRow.get(6));
        assertTrue(reasonByRow.get(8).contains("repetido"), reasonByRow.get(8));
        assertTrue(reasonByRow.get(9).contains("Falta el código"), reasonByRow.get(9));
        assertTrue(reasonByRow.get(10).contains("producto declarado"), reasonByRow.get(10));
        assertTrue(inv.get(0).raw.contains("NSOC7052-25PE"));
    }

    @Test
    void csvConSaltoDeLineaDentroDeComillasYSeparadorPuntoYComa() {
        byte[] bytes = String.join("\n",
                "nso;marca;producto;titular;ruc",
                "NSOC70523-25PE;LATTAFA;\"YARA\nCANDY\";TITULAR;20100000001",
                "NSOC7052-25PE;LATTAFA;MALO;;").getBytes(StandardCharsets.UTF_8);
        ParsedCatalog pc = parser.parse("lista.csv", bytes, YEAR);
        assertEquals(1, pc.records.size());
        assertEquals("YARA\nCANDY", pc.records.get(0).getDeclaredName());
        assertEquals(1, pc.invalidRows.size());
        assertEquals(4, pc.invalidRows.get(0).row, "la fila fisica cuenta el salto de linea dentro de comillas");
    }

    @Test
    void recargaIdempotenteYSinBorrarDatos() {
        ParsedCatalog a = parser.parse("a.csv", sampleCsv(), YEAR);
        ParsedCatalog b = parser.parse("a.csv", sampleCsv(), YEAR);
        for (int i = 0; i < a.records.size(); i++) {
            assertFalse(a.records.get(i).applyCatalogData(b.records.get(i)), "mismo archivo -> sin cambios");
        }

        NsoRecord existing = a.records.get(0);
        NsoRecord incoming = new NsoRecord(existing.getCode(), "AFNAN", "afnan", "AGUA DE PERFUME - 9AM POUR FEMME");
        incoming.setTitular("OTRO TITULAR");
        assertTrue(existing.applyCatalogData(incoming));
        assertEquals("OTRO TITULAR", existing.getTitular());
        assertEquals(25.0, existing.getUsdKg(), "un dato ausente en el archivo nuevo no borra el anterior");
        assertEquals("20999999902", existing.getRuc());
    }

    // ============================ XLSX ============================

    private static void row(Sheet sh, int idx, Object... values) {
        Row r = sh.createRow(idx);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) continue;
            if (v instanceof Number n) r.createCell(i).setCellValue(n.doubleValue());
            else if (v instanceof LocalDate d) r.createCell(i).setCellValue(d);
            else r.createCell(i).setCellValue(v.toString());
        }
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws Exception {
        try (wb; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static byte[] finalStyleWorkbook() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet con = wb.createSheet("Con NSO");
        row(con, 0, "Estado", "NSO", "Marca", "Producto (tu lista)", "Producto en la NSO", "Titular de la NSO",
                "RUC titular", "Fuente", "SKU", "UPC", "Precio USD", "Lista");
        row(con, 1, "ALTA", "NSOC78874-26PE", "Afnan", "Afnan Supremacy Gala W EDP 3.0 oz (tester)",
                "EXTRACTO DE PERFUME-SUPREMACY GALA", "TITULAR DEMO DOS S.A.C",
                "20999999902", "CSV", "PERF-AFNA-38", null, 10.5, "Oasis");
        row(con, 2, "MEDIA", "NSOC77051-25PE", "Afnan", "Supremacy Silver 3.4 Oz Edp Men",
                "AGUA DE PERFUME SUPREMACY SILVER", "TITULAR DEMO DOS S.A.C", "20999999902", "CSV", "ZX_PE-AFN-M-000976",
                "6290171000976", 16.9, "Zimaxx");
        row(con, 3, "ALTA", "NSOC77051-25PE", "Afnan", "Supremacy Silver 3.4 Oz Edp Men (otra fila)",
                "AGUA DE PERFUME SUPREMACY SILVER", "TITULAR DEMO DOS S.A.C", "20999999902", "CSV", "ZX_PE-AFN-M-000976B",
                6290171000976L, 16.9, "ZIMAXX INC");
        row(con, 4, "ALTA", "NSOC1-25PE", "Afnan", "Codigo roto", "X", "Y", "1", "CSV", "SKU-1", null, 1, "Oasis");

        Sheet falta = wb.createSheet("Marca con NSO - falta prod");
        row(falta, 0, "Marca", "Producto (tu lista)", "SKU", "UPC", "Precio USD", "Lista",
                "Titular que ya trae la marca", "RUC titular");
        row(falta, 1, "Afnan", "9 Am 3.4 Oz Edp Unisex", "ZX_PE-AFN-M-002345", "6290171002345", 11.5, "Zimaxx", "TITULAR DEMO DOS S.A.C", "20999999902");

        Sheet cat = wb.createSheet("Hoja con otro nombre");
        row(cat, 0, "Catalogo NSO Peru (titulo)");
        row(cat, 2, "NSO", "Marca", "Producto declarado", "Titular", "RUC", "Tipo", "Fuente");
        row(cat, 3, "NSOC73205-25PE", "ADOLFO DOMINGUEZ", "AD ADN NEROLI ECSTASY 100ML", "TITULAR DEMO UNO S.A",
                20999999901L, "Disenador", "CSV");
        row(cat, 4, "NSOC70409-25PE", "COACH", "COACH EDT", null, null, null, "Aduanet 2026");
        row(cat, 5, "NSOC38563-25CO", "COACH", "COACH FLORAL EDP", null, null, null, "Aduanet 2026");

        Sheet sin = wb.createSheet("Sin NSO por marca");
        row(sin, 0, "Marca", "Lista", "Productos sin NSO");
        row(sin, 1, "New Brand", "Zimaxx", 93);
        return toBytes(wb);
    }

    @Test
    void xlsxDetectaHojasPorEncabezadoConAduanetSinTitular() throws Exception {
        ParsedCatalog pc = parser.parse("NSO-perfumes-FINAL.xlsx", finalStyleWorkbook(), YEAR);

        assertEquals("XLSX", pc.fileType);
        assertEquals(3, pc.records.size());
        Map<String, NsoRecord> byCode = pc.records.stream().collect(Collectors.toMap(NsoRecord::getCode, Function.identity()));

        NsoRecord ad = byCode.get("NSOC73205-25PE");
        assertEquals("20999999901", ad.getRuc(), "RUC numerico de Excel -> texto sin notacion cientifica");
        assertEquals("Disenador", ad.getTipo());
        assertEquals(NsoRecord.SOURCE_XLSX, ad.getSource());
        assertEquals("adolfo dominguez", ad.getBrandKey());

        List<NsoRecord> aduanet = pc.records.stream().filter(r -> NsoRecord.SOURCE_ADUANET.equals(r.getSource())).toList();
        assertEquals(2, aduanet.size());
        assertTrue(aduanet.stream().allMatch(r -> r.getTitular() == null && r.getRuc() == null && r.getTipo() == null));
        assertEquals("CO", byCode.get("NSOC38563-25CO").getCountry());

        // Investigacion: 3 links validos + 1 invalido; la hoja "falta prod" (sin NSO) se ignora.
        assertEquals(3, pc.researchLinks.size());
        assertEquals(1, pc.invalidRows.size());
        assertEquals(5, pc.invalidRows.get(0).row);
        assertTrue(pc.invalidRows.get(0).reason.contains("Con NSO"), pc.invalidRows.get(0).reason);

        NsoCatalogParser.ResearchLink gala = pc.researchLinks.get(0);
        assertEquals("NSOC78874-26PE", gala.code);
        assertEquals("ALTA", gala.estado);
        assertEquals("oasis|PERF-AFNA-38", gala.skuAliasKey);
        assertNull(gala.gtin);
        assertEquals(10.5, gala.priceUsd);
        assertEquals("06290171000976", pc.researchLinks.get(2).gtin, "UPC numerico de Excel");
        assertEquals("zimaxx|ZX_PE-AFN-M-000976B", pc.researchLinks.get(2).skuAliasKey, "ZIMAXX INC == Zimaxx");

        List<NsoAlias> aliases = pc.researchAliases();
        Set<String> keys = aliases.stream().map(a -> a.getKind() + ":" + a.getAliasKey()).collect(Collectors.toSet());
        assertEquals(Set.of("SUPPLIER_SKU:oasis|PERF-AFNA-38", "SUPPLIER_SKU:zimaxx|ZX_PE-AFN-M-000976",
                "SUPPLIER_SKU:zimaxx|ZX_PE-AFN-M-000976B", "GTIN:06290171000976"), keys);
        assertEquals(4, aliases.size(), "el mismo GTIN al mismo codigo no se repite");
        assertTrue(aliases.stream().allMatch(a -> NsoAlias.ORIGIN_RESEARCH.equals(a.getOrigin())
                && NsoAlias.POSITIVE.equals(a.getPolarity()) && a.getNsoCode() != null));
    }

    @Test
    void xlsxConColumnasExtraDelCompleto() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet cat = wb.createSheet("Catalogo NSO Peru");
        row(cat, 0, "NSO", "Tipo", "Marca", "Producto declarado", "Titular", "RUC", "Origen", "Año NSO",
                "US$/kg", "Última importación");
        row(cat, 1, "NSOC73205-25PE", "Disenador", "ADOLFO DOMINGUEZ", "AD ADN NEROLI ECSTASY 100ML",
                "TITULAR DEMO UNO S.A", 20999999901L, "Spain", 1999, 25.83, "16/06/2026");
        row(cat, 2, "NSOC72689-25PE", "Disenador", "ADOLFO DOMINGUEZ", "AD ADN ROSA SPICY 100 ML TT",
                "TITULAR DEMO UNO S.A", 20999999901L, "Spain", 2025, 38.22, LocalDate.of(2026, 5, 19));
        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
        cat.getRow(2).getCell(9).setCellStyle(dateStyle);

        ParsedCatalog pc = parser.parse("NSO-perfumes-COMPLETO.xlsx", toBytes(wb), YEAR);
        assertEquals(2, pc.records.size());
        assertTrue(pc.invalidRows.isEmpty());
        NsoRecord r1 = pc.records.get(0);
        assertEquals(25.83, r1.getUsdKg());
        assertEquals("16/06/2026", r1.getLastImportDate());
        assertEquals("Spain", r1.getOrigen());
        assertEquals(2025, r1.getNsoYear(), "Ano NSO del archivo se ignora");
        assertEquals("19/05/2026", pc.records.get(1).getLastImportDate(), "celda fecha real de Excel");
    }

    // ============================ no reconocidos ============================

    @Test
    void archivoNoReconocidoLanzaMensajeClaro() throws Exception {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("precios.csv", "sku,name,price\r\n1,a,2\r\n".getBytes(StandardCharsets.UTF_8), YEAR));
        assertTrue(e1.getMessage().contains("nso"), e1.getMessage());

        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sh = wb.createSheet("Price List");
        row(sh, 0, "UPC", "Sku", "Brand", "Title Product", "Price");
        row(sh, 1, "6290171000976", "ZX-1", "Afnan", "Supremacy Silver", 16.9);
        byte[] supplierList = toBytes(wb);
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("US Wholesale.xlsx", supplierList, YEAR));
        assertTrue(e2.getMessage().contains("No reconocimos"), e2.getMessage());

        assertThrows(IllegalArgumentException.class, () -> parser.parse("vacio.csv", new byte[0], YEAR));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("roto.xlsx", "esto no es un excel".getBytes(StandardCharsets.UTF_8), YEAR));
    }

    // ============================ archivo real ============================

    @Test
    void archivoRealFinalSiExiste() throws Exception {
        Path real = Path.of("C:/Users/Fily/Negocios/PERFUMES/analisis de competencia/NSO-perfumes-FINAL.xlsx");
        Assumptions.assumeTrue(Files.exists(real), "Sin el archivo real (esta fuera del repo): se salta");

        ParsedCatalog pc = parser.parse(real.getFileName().toString(), Files.readAllBytes(real), YEAR);
        assertEquals("XLSX", pc.fileType);
        assertEquals(1693, pc.records.size());
        long aduanetSinTitular = pc.records.stream()
                .filter(r -> NsoRecord.SOURCE_ADUANET.equals(r.getSource()) && r.getTitular() == null)
                .count();
        assertEquals(78, aduanetSinTitular);
        assertEquals(0, pc.invalidRows.size(), pc.invalidRows.stream().limit(5).map(r -> r.row + ":" + r.reason).toList().toString());
        assertEquals(298, pc.researchLinks.size());
        assertTrue(pc.records.stream().allMatch(r -> r.getNsoYear() != null && r.getCountry() != null));
        assertTrue(pc.records.stream().anyMatch(r -> "NSOC780229-25PE".equals(r.getCode())), "codigo de 6 digitos");
    }

    @Test
    void csvRealSiExiste() throws Exception {
        Path real = Path.of("C:/Users/Fily/Negocios/PERFUMES/analisis de competencia/NSO_perfumes_Peru.csv");
        Assumptions.assumeTrue(Files.exists(real), "Sin el archivo real (esta fuera del repo): se salta");

        ParsedCatalog pc = parser.parse(real.getFileName().toString(), Files.readAllBytes(real), YEAR);
        assertEquals("CSV", pc.fileType);
        assertEquals(1615, pc.records.size());
        assertEquals(0, pc.invalidRows.size(), pc.invalidRows.stream().limit(5).map(r -> r.row + ":" + r.reason).toList().toString());
        assertTrue(pc.records.stream().allMatch(r -> r.getRuc() != null && r.getRuc().matches("\\d{11}")), "RUC como texto de 11 digitos");
        assertTrue(pc.records.stream().filter(r -> r.getEan() != null).allMatch(r -> r.getEan().length() == 14));
    }
}
