package org.example.backendbvaberiaperfumes.service.excelfill;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Adaptador que sabe DÓNDE están, en el Excel de un proveedor, la columna de UPC y la
 * de cantidad (y en qué hoja/fila-header). Toda la particularidad de cada formato vive
 * aquí; el llenado (SupplierExcelFiller) es genérico. Para sumar un proveedor nuevo:
 * un @Component que implemente esta interfaz.
 */
public interface SupplierExcelLayout {

    /** ¿Este adaptador es el del proveedor dado? */
    boolean supports(String supplierName);

    /** Localiza hoja + columnas; lanza IllegalArgumentException (mensaje claro) si falta algo. */
    FillLocation locate(Workbook wb);

    class FillLocation {
        public final Sheet sheet;
        public final int headerRow;
        public final int upcCol;
        public final int qtyCol;
        public FillLocation(Sheet sheet, int headerRow, int upcCol, int qtyCol) {
            this.sheet = sheet;
            this.headerRow = headerRow;
            this.upcCol = upcCol;
            this.qtyCol = qtyCol;
        }
    }
}
