package org.example.backendbvaberiaperfumes;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XLSX sintetico con los defectos REALES del Excel de FragranceSense:
 * header en fila 2 con columna "final " (espacio), celdas SOLD en el precio,
 * UPC con   (non-breaking space), cero inicial perdido, typo de checksum
 * y un precio absurdo ($2). El parser generico + canonicalizador deben
 * resolverlos sin crear identidades falsas ni romper precios.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:edgetest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class GenericImportEdgeCasesTest {

    @Autowired ExcelImportService importService;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;

    private byte[] buildFsStyleExcel() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            // Fila 0 vacia (como el archivo real); header en fila 1 (Excel fila 2).
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("DESCRIPTION");
            header.createCell(1).setCellValue("UPC");
            header.createCell(2).setCellValue("PRICE");
            header.createCell(3).setCellValue("quantity");
            header.createCell(4).setCellValue("final "); // espacio final real

            // Fila normal: UPC numerico valido, precio final con descuento.
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("LATTAFA KHAMRAH UNISEX 100ML EDP SPRAY");
            r2.createCell(1).setCellValue(6291108737194.0);
            r2.createCell(2).setCellValue(20);
            r2.createCell(3).setCellValue(20);
            r2.createCell(4).setCellValue(18);

            // SOLD en la columna final -> fuera de stock.
            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("AZZARO WANTED 100ML EDT SPRAY");
            r3.createCell(1).setCellValue(3351500016617.0);
            r3.createCell(2).setCellValue(31);
            r3.createCell(3).setCellValue(20);
            r3.createCell(4).setCellValue("SOLD");

            // UPC como texto con   pegado (celdas reales de FS).
            Row r4 = sheet.createRow(4);
            r4.createCell(0).setCellValue("ARMANI ACQUA DI GIO ELIXIR 50ML PARFUM SPRAY");
            r4.createCell(1).setCellValue(" 3614274143751");
            r4.createCell(2).setCellValue(76);
            r4.createCell(3).setCellValue(20);
            r4.createCell(4).setCellValue(73);

            // Precio absurdo ($2 por 200ml) -> sospechoso.
            Row r5 = sheet.createRow(5);
            r5.createCell(0).setCellValue("AL HAMBRA LA VOIE 200ML DEO SPRAY");
            r5.createCell(1).setCellValue(6291106068085.0); // EAN valido
            r5.createCell(2).setCellValue(2.5);
            r5.createCell(3).setCellValue(20);
            r5.createCell(4).setCellValue(2);

            // Typo de checksum (caso real: 6291106066919 es typo de ...6319).
            Row r6 = sheet.createRow(6);
            r6.createCell(0).setCellValue("LATTAFA SER AL KHULOOD BROWN 100ML EDP SPRAY");
            r6.createCell(1).setCellValue(6291106066919.0);
            r6.createCell(2).setCellValue(11);
            r6.createCell(3).setCellValue(20);
            r6.createCell(4).setCellValue(10);

            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void parseaDefectosRealesDeFragranceSense() throws Exception {
        Supplier fs = supplierRepo.findByName("FSEdgeTest")
                .orElseGet(() -> supplierRepo.save(new Supplier("FSEdgeTest", 0.0, false)));
        byte[] bytes = buildFsStyleExcel();

        ExcelImportService.ParsedData pd = importService.parse(fs, bytes, null);
        assertTrue(pd.generic, "proveedor sin parser afinado -> generico");
        assertEquals(5, pd.rows.size(), "las 5 filas de datos se leen");

        ImportPreview preview = importService.buildPreview(fs, pd);

        // Fila normal: gtin canonico y precio 'final' (18, no 20).
        ImportPreview.Line khamrah = preview.rows.stream()
                .filter(l -> l.rawTitle.contains("KHAMRAH")).findFirst().orElseThrow();
        assertEquals("06291108737194", khamrah.upc);
        assertEquals(18.0, khamrah.costUsd, 0.001, "manda la columna 'final', no PRICE");
        assertTrue(khamrah.inStock);

        // SOLD -> fuera de stock (la fila no se pierde).
        ImportPreview.Line sold = preview.rows.stream()
                .filter(l -> l.rawTitle.contains("AZZARO")).findFirst().orElseThrow();
        assertFalse(sold.inStock, "SOLD en la celda de precio = fuera de stock");

        //   no rompe el UPC.
        ImportPreview.Line nbsp = preview.rows.stream()
                .filter(l -> l.rawTitle.contains("ELIXIR")).findFirst().orElseThrow();
        assertEquals("03614274143751", nbsp.upc, "el non-breaking space se limpia");

        // Precio absurdo -> sospechoso.
        ImportPreview.Line cheap = preview.rows.stream()
                .filter(l -> l.rawTitle.contains("LA VOIE")).findFirst().orElseThrow();
        assertTrue(cheap.suspicious, "$2 esta bajo el costo minimo plausible");
        assertEquals(1, preview.suspiciousRows);

        // Typo de checksum -> sin identidad (upc null), estado CHECKSUM_FAIL.
        ImportPreview.Line typo = preview.rows.stream()
                .filter(l -> l.rawTitle.contains("KHULOOD")).findFirst().orElseThrow();
        assertNull(typo.upc, "checksum invalido jamas define identidad");
        assertEquals("CHECKSUM_FAIL", typo.gtinStatus);

        // Commit SIN aprobar la sospechosa: su oferta queda fuera de stock.
        ImportSummary summary = importService.commit(fs, pd.rows, Set.of());
        assertEquals(1, summary.getSuspiciousRows());
        List<SupplierOffer> offers = offerRepo.findBySupplier_Id(fs.getId());
        SupplierOffer cheapOffer = offers.stream()
                .filter(o -> o.getRawTitle() != null && o.getRawTitle().contains("LA VOIE"))
                .findFirst().orElseThrow();
        assertFalse(cheapOffer.getInStock(), "sospechosa no aprobada = fuera de stock");
        SupplierOffer typoOffer = offers.stream()
                .filter(o -> o.getRawTitle() != null && o.getRawTitle().contains("KHULOOD"))
                .findFirst().orElseThrow();
        assertNull(typoOffer.getGtin());
        assertEquals("CHECKSUM_FAIL", typoOffer.getGtinStatus());
        assertEquals("6291106066919", typoOffer.getGtinRaw(), "el crudo queda para auditoria");
    }
}
