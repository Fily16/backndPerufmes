package org.example.backendbvaberiaperfumes;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.backendbvaberiaperfumes.service.excelfill.GenericExcelLayout;
import org.example.backendbvaberiaperfumes.service.excelfill.SupplierExcelFiller;
import org.example.backendbvaberiaperfumes.service.excelfill.ZimaxxExcelLayout;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Llenado de Excel del proveedor: escribe la cantidad SOLO por UPC, oculta las filas no
 * pedidas y preserva el resto del archivo. No requiere Spring (el filler solo necesita
 * la lista de adaptadores).
 */
class SupplierExcelFillerTest {

    private final SupplierExcelFiller filler =
            new SupplierExcelFiller(List.of(new ZimaxxExcelLayout(), new GenericExcelLayout()));

    // GTINs válidos (checksum OK) tomados de GtinCanonicalizerTest.
    private static final String A = "06290362342373";
    private static final String B = "03614274143751";
    private static final String C = "00085715167224";
    private static final String D = "03614273955546"; // pedido pero ausente del Excel

    private String canon(String upc) {
        String c = GtinCanonicalizer.canonicalize(upc).canonical14;
        assertNotNull(c, "GTIN de prueba inválido: " + upc);
        return c;
    }

    private byte[] zimaxxWorkbook() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Price List");
            sh.createRow(0).createCell(0).setCellValue("ZIMAXX - lista basura arriba");
            String[] header = {"UPC", "Sku", "Brand", "Title Product", "Price", "Type", "Qty", "Total"};
            Row h = sh.createRow(1);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            dataRow(sh, 2, A, "Lattafa", "Khamrah 100ml", 12.0);
            dataRow(sh, 3, B, "Armaf", "Club de Nuit 105ml", 25.0);
            dataRow(sh, 4, C, "Rasasi", "Hawas 100ml", 20.0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void dataRow(Sheet sh, int r, String upc, String brand, String title, double price) {
        Row row = sh.createRow(r);
        row.createCell(0).setCellValue(upc);      // UPC como texto (conserva ceros)
        row.createCell(1).setCellValue("SKU" + r);
        row.createCell(2).setCellValue(brand);
        row.createCell(3).setCellValue(title);
        row.createCell(4).setCellValue(price);
        row.createCell(5).setCellValue("Available");
        // Qty (6) vacía; Total (7) fórmula = Qty*Price
        row.createCell(7).setCellFormula("G" + (r + 1) + "*E" + (r + 1));
    }

    @Test
    void zimaxxEscribeCantidadPorUpcYOcultaLasNoPedidas() throws Exception {
        Map<String, Integer> map = new HashMap<>();
        map.put(canon(A), 2);
        map.put(canon(B), 3);
        map.put(canon(D), 5); // no está en el Excel

        SupplierExcelFiller.FillResult res = filler.fill(zimaxxWorkbook(), "Zimaxx", map);

        assertEquals(2, res.report.updated);
        assertEquals(2, res.report.found);
        assertEquals(1, res.report.hiddenRows, "solo la fila de C (no pedida) se oculta");
        assertTrue(res.matchedUpcs.contains(canon(A)));
        assertTrue(res.matchedUpcs.contains(canon(B)));
        assertFalse(res.matchedUpcs.contains(canon(C)));
        assertFalse(res.matchedUpcs.contains(canon(D)), "D no está en el Excel -> no matcheado");
        assertEquals("Price List", res.report.sheetName);

        // Reabrir el archivo devuelto y verificar cantidades + filas ocultas.
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(res.bytes))) {
            Sheet sh = wb.getSheetAt(0);
            assertEquals(2.0, sh.getRow(2).getCell(6).getNumericCellValue(), 0.001, "A -> Qty 2");
            assertEquals(3.0, sh.getRow(3).getCell(6).getNumericCellValue(), 0.001, "B -> Qty 3");
            assertEquals(0.0, sh.getRow(4).getCell(6).getNumericCellValue(), 0.001, "C -> Qty 0");
            assertFalse(sh.getRow(2).getZeroHeight(), "la fila pedida queda visible");
            assertTrue(sh.getRow(4).getZeroHeight(), "la fila no pedida queda oculta");
            // El resto del contenido se preserva (título/marca).
            assertEquals("Khamrah 100ml", sh.getRow(2).getCell(3).getStringCellValue());
            assertEquals("G3*E3", sh.getRow(2).getCell(7).getCellFormula(), "la fórmula Total se conserva");
        }
    }

    @Test
    void genericoUbicaColumnasPorAliasQuantity() throws Exception {
        byte[] bytes;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Hoja1");
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("Brand");
            h.createCell(1).setCellValue("Description");
            h.createCell(2).setCellValue("Barcode");   // alias de UPC
            h.createCell(3).setCellValue("Price");
            h.createCell(4).setCellValue("Quantity");  // alias de cantidad
            Row r1 = sh.createRow(1);
            r1.createCell(0).setCellValue("Lattafa");
            r1.createCell(1).setCellValue("Yara 100ml");
            r1.createCell(2).setCellValue(A);
            r1.createCell(3).setCellValue(30.0);
            Row r2 = sh.createRow(2);
            r2.createCell(0).setCellValue("Armaf");
            r2.createCell(1).setCellValue("Ventana 100ml");
            r2.createCell(2).setCellValue(B);
            r2.createCell(3).setCellValue(22.0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }

        Map<String, Integer> map = new HashMap<>();
        map.put(canon(A), 4);

        SupplierExcelFiller.FillResult res = filler.fill(bytes, "FragranceSense", map);
        assertEquals(1, res.report.updated);
        assertEquals(1, res.report.hiddenRows, "la fila de B (no pedida) se oculta");

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(res.bytes))) {
            Sheet sh = wb.getSheetAt(0);
            assertEquals(4.0, sh.getRow(1).getCell(4).getNumericCellValue(), 0.001, "A -> Quantity 4");
            assertTrue(sh.getRow(2).getZeroHeight());
        }
    }

    @Test
    void archivoInvalidoLanzaErrorClaro() {
        byte[] basura = "esto no es un excel".getBytes();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> filler.fill(basura, "Zimaxx", Map.of()));
        assertTrue(ex.getMessage().toLowerCase().contains("excel"));
    }
}
