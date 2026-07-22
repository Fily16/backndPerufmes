package org.example.backendbvaberiaperfumes.service.excelfill;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.backendbvaberiaperfumes.dto.FillReport;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Llena la columna de cantidad del Excel ORIGINAL del proveedor a partir del pedido ya
 * calculado. El match es por UPC, pero de forma ROBUSTA: si un producto no tiene UPC
 * canónico (o su UPC no está en el Excel), cae a claves EXACTAS del mismo proveedor
 * —dígitos crudos del código, SKU y título tal como se importaron (SupplierOffer)—, no a
 * texto difuso. Las filas no pedidas quedan en 0 y ocultas. Se re-guarda el MISMO workbook
 * con POI (preserva estilos/fórmulas/anchos/imágenes/validaciones); el original nunca se toca.
 */
@Service
public class SupplierExcelFiller {

    private final List<SupplierExcelLayout> layouts; // @Order: dedicados primero, genérico al final

    public SupplierExcelFiller(List<SupplierExcelLayout> layouts) {
        this.layouts = layouts;
    }

    /** Una línea del pedido: cantidad + las claves con que ubicarla en el Excel del proveedor. */
    public static class OrderLine {
        public String canonUpc;   // GTIN-14 canónico (o null)
        public String rawDigits;  // dígitos crudos del código, aunque el checksum falle (o null)
        public String sku;        // SKU del proveedor (o null)
        public String title;      // título tal como se importó (SupplierOffer.rawTitle) o marca+nombre
        public int quantity;
        // meta para el reporte:
        public String gtin;
        public String brand;
        public String name;
    }

    public static class FillResult {
        public final byte[] bytes;
        public final FillReport report;
        public FillResult(byte[] bytes, FillReport report) { this.bytes = bytes; this.report = report; }
    }

    public FillResult fill(byte[] bytes, String supplierName, List<OrderLine> lines) throws Exception {
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

            // 1) Indexar cada fila de datos con sus claves.
            List<XRow> xrows = new ArrayList<>();
            for (int r = loc.headerRow + 1; r <= loc.sheet.getLastRowNum(); r++) {
                Row row = loc.sheet.getRow(r);
                if (ExcelCells.rowEmpty(row)) continue; // separadores/blancos: no se tocan
                XRow xr = new XRow();
                xr.row = row;
                String rawUpc = ExcelCells.rawId(row, loc.upcCol);
                xr.canon = GtinCanonicalizer.canonicalize(rawUpc).canonical14;
                xr.digits = digits(rawUpc);
                xr.sku = norm(ExcelCells.rawId(row, loc.skuCol));
                xr.title = norm(ExcelCells.str(row, loc.nameCol));
                xrows.add(xr);
            }
            report.totalRows = xrows.size();

            // 2) Emparejar cada línea del pedido por prioridad de clave (UPC canónico -> dígitos -> SKU -> título).
            int byCode = 0, byName = 0;
            for (OrderLine line : lines) {
                XRow hit = firstFree(xrows, x -> line.canonUpc != null && line.canonUpc.equals(x.canon));
                boolean code = hit != null;
                if (hit == null) hit = firstFree(xrows, x -> nonBlank(line.rawDigits) && line.rawDigits.equals(x.digits));
                if (hit != null && !code) code = true; // dígitos crudos siguen siendo "por código"
                if (hit == null) hit = firstFree(xrows, x -> nonBlank(line.sku) && norm(line.sku).equals(x.sku));
                if (hit == null) hit = firstFree(xrows, x -> nonBlank(line.title) && norm(line.title).equals(x.title));

                if (hit != null) {
                    hit.used = true;
                    setQty(hit.row, loc.qtyCol, line.quantity);
                    if (code) byCode++; else byName++;
                } else {
                    report.notFound.add(new FillReport.Missing(line.gtin, line.brand, line.name, line.quantity));
                }
            }

            // 3) Filas del Excel no pedidas -> 0 + ocultas.
            int hidden = 0;
            for (XRow x : xrows) {
                if (x.used) continue;
                setQty(x.row, loc.qtyCol, 0);
                x.row.setZeroHeight(true);
                hidden++;
            }

            report.updated = byCode + byName;
            report.found = report.updated;
            report.matchedByCode = byCode;
            report.matchedByName = byName;
            report.hiddenRows = hidden;

            wb.setForceFormulaRecalculation(true); // el Total (fórmula) se recalcula al abrir
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            report.durationMs = System.currentTimeMillis() - t0;
            return new FillResult(out.toByteArray(), report);
        } finally {
            wb.close();
        }
    }

    // ---- helpers ----

    private static class XRow {
        Row row;
        String canon;   // UPC canónico
        String digits;  // dígitos crudos
        String sku;     // SKU normalizado
        String title;   // título normalizado
        boolean used;
    }

    private interface RowPredicate { boolean test(XRow x); }

    /** Primera fila libre que cumple el predicado (evita re-usar una fila ya asignada). */
    private XRow firstFree(List<XRow> xrows, RowPredicate p) {
        for (XRow x : xrows) if (!x.used && p.test(x)) return x;
        return null;
    }

    private void setQty(Row row, int col, int qty) {
        if (col < 0) return;
        Cell c = row.getCell(col);
        if (c == null) c = row.createCell(col);
        c.setCellValue(qty);
    }

    private static boolean nonBlank(String s) { return s != null && !s.isBlank(); }

    /** Solo dígitos (para comparar códigos crudos sin importar formato). */
    private static String digits(String s) {
        if (s == null) return null;
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    /** Normaliza texto (sin acentos, minúsculas, solo alfanumérico y espacios simples). */
    private static String norm(String s) {
        if (s == null) return null;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? null : n;
    }
}
