package org.example.backendbvaberiaperfumes.service.excelfill;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.backendbvaberiaperfumes.dto.FillReport;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Llena la columna de cantidad del Excel ORIGINAL del proveedor a partir del pedido ya
 * calculado (mapa UPC canónico -> cantidad). Match EXCLUSIVAMENTE por UPC. Las filas que
 * no están en el pedido quedan en 0 y ocultas. Se re-guarda el MISMO workbook con POI,
 * preservando estilos/fórmulas/anchos/imágenes/validaciones. El original nunca se toca.
 */
@Service
public class SupplierExcelFiller {

    private final List<SupplierExcelLayout> layouts; // inyectados en orden (@Order): dedicados primero, genérico al final

    public SupplierExcelFiller(List<SupplierExcelLayout> layouts) {
        this.layouts = layouts;
    }

    public static class FillResult {
        public final byte[] bytes;
        public final FillReport report;
        public final Set<String> matchedUpcs;  // UPCs canónicos del pedido efectivamente ubicados
        public FillResult(byte[] bytes, FillReport report, Set<String> matchedUpcs) {
            this.bytes = bytes; this.report = report; this.matchedUpcs = matchedUpcs;
        }
    }

    public FillResult fill(byte[] bytes, String supplierName, Map<String, Integer> qtyByUpc) throws Exception {
        long t0 = System.currentTimeMillis();
        FillReport report = new FillReport();
        report.supplierName = supplierName;

        Workbook wb;
        try {
            wb = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("No se pudo leer el Excel (¿archivo dañado o formato inválido?).");
        }

        try {
            SupplierExcelLayout layout = layouts.stream()
                    .filter(l -> l.supports(supplierName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No hay un formato de Excel para el proveedor " + supplierName + "."));
            SupplierExcelLayout.FillLocation loc = layout.locate(wb);
            report.sheetName = loc.sheet.getSheetName();

            Set<String> matched = new HashSet<>();  // UPCs ya escritos (además detecta duplicados)
            int rows = 0, updated = 0, hidden = 0;

            for (int r = loc.headerRow + 1; r <= loc.sheet.getLastRowNum(); r++) {
                Row row = loc.sheet.getRow(r);
                if (ExcelCells.rowEmpty(row)) continue;   // separadores/blancos: no se tocan
                rows++;

                String rawUpc = ExcelCells.rawId(row, loc.upcCol);
                String canon = GtinCanonicalizer.canonicalize(rawUpc).canonical14;
                Integer qty = (canon != null) ? qtyByUpc.get(canon) : null;

                if (qty != null && qty > 0 && matched.add(canon)) {
                    setQty(row, loc.qtyCol, qty);
                    updated++;
                } else {
                    // No pedido, UPC vacío/ilegible, o repetido en el Excel -> 0 + oculta.
                    if (qty != null && qty > 0 && canon != null) report.duplicateUpcs.add(canon);
                    setQty(row, loc.qtyCol, 0);
                    row.setZeroHeight(true);
                    hidden++;
                }
            }

            report.totalRows = rows;
            report.updated = updated;
            report.found = matched.size();
            report.hiddenRows = hidden;

            wb.setForceFormulaRecalculation(true); // el Total (fórmula) se recalcula al abrir
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            report.durationMs = System.currentTimeMillis() - t0;
            return new FillResult(out.toByteArray(), report, matched);
        } finally {
            wb.close();
        }
    }

    private void setQty(Row row, int col, int qty) {
        Cell c = row.getCell(col);
        if (c == null) c = row.createCell(col);
        c.setCellValue(qty);
    }
}
