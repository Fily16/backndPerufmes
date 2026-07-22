package org.example.backendbvaberiaperfumes;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.backendbvaberiaperfumes.service.excelfill.GenericExcelLayout;
import org.example.backendbvaberiaperfumes.service.excelfill.SupplierExcelFiller;
import org.example.backendbvaberiaperfumes.service.excelfill.SupplierExcelFiller.OrderLine;
import org.example.backendbvaberiaperfumes.service.excelfill.ZimaxxExcelLayout;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Llenado de Excel del proveedor: escribe la cantidad ubicando cada línea del pedido de forma
 * ROBUSTA (UPC canónico -> dígitos crudos -> SKU -> título, todo EXACTO), oculta las filas no
 * pedidas y preserva el resto del archivo. No requiere Spring (el filler solo necesita la lista
 * de adaptadores).
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

    /** Línea que se ubica por UPC (dígitos crudos = el mismo código). */
    private OrderLine byUpc(String upc, int qty) {
        OrderLine o = new OrderLine();
        o.canonUpc = canon(upc);
        o.rawDigits = upc;
        o.quantity = qty;
        o.gtin = upc;
        return o;
    }

    /** Línea SIN UPC que solo se puede ubicar por SKU. */
    private OrderLine bySku(String sku, int qty) {
        OrderLine o = new OrderLine();
        o.sku = sku;
        o.quantity = qty;
        return o;
    }

    /** Línea SIN UPC ni SKU que solo se puede ubicar por título. */
    private OrderLine byTitle(String title, int qty) {
        OrderLine o = new OrderLine();
        o.title = title;
        o.name = title;
        o.quantity = qty;
        return o;
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
        List<OrderLine> lines = List.of(byUpc(A, 2), byUpc(B, 3), byUpc(D, 5)); // D no está en el Excel

        SupplierExcelFiller.FillResult res = filler.fill(zimaxxWorkbook(), "Zimaxx", lines);

        assertEquals(2, res.report.updated);
        assertEquals(2, res.report.found);
        assertEquals(2, res.report.matchedByCode, "A y B se ubican por UPC");
        assertEquals(0, res.report.matchedByName);
        assertEquals(1, res.report.hiddenRows, "solo la fila de C (no pedida) se oculta");
        assertEquals(1, res.report.notFound.size(), "D no está en el Excel");
        assertEquals(D, res.report.notFound.get(0).gtin);
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
    void ubicaProductosSinUpcPorSkuYPorTitulo() throws Exception {
        // Excel con un producto con UPC y dos SIN UPC (uno con SKU, otro solo con título).
        byte[] bytes;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Price List");
            String[] header = {"UPC", "Sku", "Brand", "Title Product", "Price", "Type", "Qty", "Total"};
            Row h = sh.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            dataRow(sh, 1, A, "Lattafa", "Khamrah 100ml", 12.0);
            Row r2 = sh.createRow(2);            // sin UPC -> se ubica por SKU
            r2.createCell(0).setCellValue("");
            r2.createCell(1).setCellValue("ZX-777");
            r2.createCell(2).setCellValue("Ard Al Zaafaran");
            r2.createCell(3).setCellValue("Sabah Al Ward 100ml");
            r2.createCell(4).setCellValue(15.0);
            Row r3 = sh.createRow(3);            // sin UPC ni SKU -> se ubica por título
            r3.createCell(0).setCellValue("");
            r3.createCell(1).setCellValue("");
            r3.createCell(2).setCellValue("Lattafa");
            r3.createCell(3).setCellValue("Yara Tous 100ml");
            r3.createCell(4).setCellValue(20.0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }

        List<OrderLine> lines = List.of(
                byUpc(A, 2),
                bySku("ZX-777", 4),
                byTitle("Yara Tous 100ml", 6));

        SupplierExcelFiller.FillResult res = filler.fill(bytes, "Zimaxx", lines);

        assertEquals(3, res.report.updated, "los 3 se ubican");
        assertEquals(1, res.report.matchedByCode, "A por UPC");
        assertEquals(2, res.report.matchedByName, "SKU + título (fallback robusto)");
        assertEquals(0, res.report.hiddenRows, "las 3 filas fueron pedidas");
        assertTrue(res.report.notFound.isEmpty());

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(res.bytes))) {
            Sheet sh = wb.getSheetAt(0);
            assertEquals(2.0, sh.getRow(1).getCell(6).getNumericCellValue(), 0.001, "A -> Qty 2");
            assertEquals(4.0, sh.getRow(2).getCell(6).getNumericCellValue(), 0.001, "SKU -> Qty 4");
            assertEquals(6.0, sh.getRow(3).getCell(6).getNumericCellValue(), 0.001, "título -> Qty 6");
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

        SupplierExcelFiller.FillResult res = filler.fill(bytes, "FragranceSense", List.of(byUpc(A, 4)));
        assertEquals(1, res.report.updated);
        assertEquals(1, res.report.matchedByCode);
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
                () -> filler.fill(basura, "Zimaxx", List.of()));
        assertTrue(ex.getMessage().toLowerCase().contains("excel"));
    }
}
