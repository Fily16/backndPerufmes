package org.example.backendbvaberiaperfumes.service.excelfill;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Helpers estáticos para leer celdas de POI de forma segura (mismo criterio que
 * AbstractSupplierParser, pero desacoplados para el llenado de Excel).
 */
public final class ExcelCells {
    private ExcelCells() { }

    /** Mapa header (texto en minúsculas) -> índice de columna. */
    public static Map<String, Integer> headerMap(Row header) {
        Map<String, Integer> m = new HashMap<>();
        if (header == null) return m;
        for (int i = header.getFirstCellNum(); i < header.getLastCellNum(); i++) {
            String s = str(header.getCell(i));
            if (s != null && !s.isBlank()) m.putIfAbsent(s.toLowerCase().trim(), i);
        }
        return m;
    }

    public static int findCol(Map<String, Integer> headers, String... aliases) {
        for (String a : aliases) {
            Integer idx = headers.get(a.toLowerCase().trim());
            if (idx != null) return idx;
        }
        return -1;
    }

    public static String str(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> new BigDecimal(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> {
                try { yield c.getStringCellValue().trim(); }
                catch (Exception e) { yield String.valueOf(c.getNumericCellValue()); }
            }
            default -> null;
        };
    }

    public static String str(Row row, int col) {
        if (row == null || col < 0) return null;
        return str(row.getCell(col));
    }

    /** Identificador crudo (UPC/SKU) como dígitos, evitando notación científica. */
    public static String rawId(Row row, int col) {
        if (row == null || col < 0) return null;
        Cell c = row.getCell(col);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) {
            return new BigDecimal(c.getNumericCellValue()).toBigInteger().toString();
        }
        String s = str(c);
        return s == null ? null : s.trim();
    }

    public static boolean rowEmpty(Row row) {
        if (row == null) return true;
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            String s = str(row.getCell(i));
            if (s != null && !s.isBlank()) return false;
        }
        return true;
    }
}
