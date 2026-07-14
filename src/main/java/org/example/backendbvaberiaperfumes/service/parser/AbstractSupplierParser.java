package org.example.backendbvaberiaperfumes.service.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Helpers comunes para leer celdas de POI de forma segura. */
public abstract class AbstractSupplierParser implements SupplierExcelParser {

    /** Canonicaliza el codigo de barras y deja gtin/gtinRaw/gtinStatus en la fila. */
    protected void applyGtin(ParsedRow pr, Object raw) {
        GtinCanonicalizer.GtinResult gr = GtinCanonicalizer.canonicalize(raw);
        pr.gtin = gr.canonical14;
        pr.gtinRaw = gr.rawDigits;
        pr.gtinStatus = gr.status.name();
    }

    protected String cellStr(Row row, int col) {
        if (row == null || col < 0) return null;
        return cellStr(row.getCell(col));
    }

    protected String cellStr(Cell c) {
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

    /** Identificador crudo (UPC/SKU) como digitos, evitando notacion cientifica. */
    protected String cellRawId(Row row, int col) {
        if (row == null || col < 0) return null;
        Cell c = row.getCell(col);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) {
            return new BigDecimal(c.getNumericCellValue()).toBigInteger().toString();
        }
        String s = cellStr(c);
        return s == null ? null : s.trim();
    }

    protected Double cellNum(Row row, int col) {
        if (row == null || col < 0) return null;
        Cell c = row.getCell(col);
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC) return c.getNumericCellValue();
            if (c.getCellType() == CellType.FORMULA) return c.getNumericCellValue();
            String s = cellStr(c);
            if (s == null || s.isBlank()) return null;
            return Double.parseDouble(s.replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    protected boolean rowEmpty(Row row) {
        if (row == null) return true;
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            String s = cellStr(row.getCell(i));
            if (s != null && !s.isBlank()) return false;
        }
        return true;
    }

    /** Mapa header (texto en minusculas, sin espacios extra) -> indice de columna. */
    protected Map<String, Integer> headerMap(Row header) {
        Map<String, Integer> map = new HashMap<>();
        if (header == null) return map;
        for (int i = header.getFirstCellNum(); i < header.getLastCellNum(); i++) {
            String s = cellStr(header.getCell(i));
            if (s != null && !s.isBlank()) map.put(s.toLowerCase().trim(), i);
        }
        return map;
    }

    protected int col(Map<String, Integer> headers, String... candidates) {
        for (String cand : candidates) {
            Integer idx = headers.get(cand.toLowerCase().trim());
            if (idx != null) return idx;
        }
        return -1;
    }
}
