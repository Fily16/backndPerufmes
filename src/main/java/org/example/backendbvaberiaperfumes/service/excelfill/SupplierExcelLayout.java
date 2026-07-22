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
        public final int nameCol;   // columna de descripción/título (-1 si no hay) — fallback por nombre
        public final int skuCol;    // columna de SKU del proveedor (-1 si no hay) — fallback por SKU
        public FillLocation(Sheet sheet, int headerRow, int upcCol, int qtyCol, int nameCol, int skuCol) {
            this.sheet = sheet;
            this.headerRow = headerRow;
            this.upcCol = upcCol;
            this.qtyCol = qtyCol;
            this.nameCol = nameCol;
            this.skuCol = skuCol;
        }
    }
}
