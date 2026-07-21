package org.example.backendbvaberiaperfumes.service.excelfill;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Zimaxx "Price List": header UPC | Sku | Brand | Title Product | Price | Type | Qty | Total.
 * Se escribe la columna Qty; el Total (fórmula) se recalcula al abrir.
 */
@Component
@Order(10)
public class ZimaxxExcelLayout implements SupplierExcelLayout {

    @Override
    public boolean supports(String supplierName) {
        return supplierName != null && supplierName.toLowerCase().contains("zimaxx");
    }

    @Override
    public FillLocation locate(Workbook wb) {
        Sheet sheet = wb.getSheetAt(0);
        for (int r = 0; r <= Math.min(20, sheet.getLastRowNum()); r++) {
            Map<String, Integer> h = ExcelCells.headerMap(sheet.getRow(r));
            if (h.containsKey("upc") && h.containsKey("brand")
                    && (h.containsKey("title product") || h.containsKey("title"))) {
                int upc = ExcelCells.findCol(h, "upc");
                int qty = ExcelCells.findCol(h, "qty", "quantity", "order", "cantidad");
                if (upc < 0) throw new IllegalArgumentException("No se encontró la columna UPC en el Excel de Zimaxx.");
                if (qty < 0) throw new IllegalArgumentException("No se encontró la columna Qty en el Excel de Zimaxx.");
                return new FillLocation(sheet, r, upc, qty);
            }
        }
        throw new IllegalArgumentException("No se encontró el encabezado (UPC/Brand/Title) en el Excel de Zimaxx.");
    }
}
