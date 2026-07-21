package org.example.backendbvaberiaperfumes.service.excelfill;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Adaptador genérico (fallback para cualquier proveedor sin formato dedicado, ej. Fragsense):
 * busca en las primeras filas un encabezado que tenga a la vez una columna de UPC y una de
 * cantidad, por nombres típicos. @Order alto = se elige de último (después de los dedicados).
 */
@Component
@Order(1000)
public class GenericExcelLayout implements SupplierExcelLayout {

    private static final String[] UPC_ALIASES =
            {"upc", "barcode", "ean", "gtin", "codigo", "código", "cod barras", "upc code", "bar code"};
    private static final String[] QTY_ALIASES =
            {"quantity", "qty", "order", "cantidad", "orden", "units", "order qty", "qty ordered", "pedido"};

    @Override
    public boolean supports(String supplierName) {
        return true; // fallback: sirve para cualquiera
    }

    @Override
    public FillLocation locate(Workbook wb) {
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            int lastScan = Math.min(25, sheet.getLastRowNum());
            for (int r = 0; r <= lastScan; r++) {
                Map<String, Integer> h = ExcelCells.headerMap(sheet.getRow(r));
                int upc = ExcelCells.findCol(h, UPC_ALIASES);
                int qty = ExcelCells.findCol(h, QTY_ALIASES);
                if (upc >= 0 && qty >= 0) {
                    return new FillLocation(sheet, r, upc, qty);
                }
            }
        }
        throw new IllegalArgumentException(
                "No se encontró un encabezado con columna de UPC y de cantidad (Quantity/Qty/Order). Revisa el archivo del proveedor.");
    }
}
