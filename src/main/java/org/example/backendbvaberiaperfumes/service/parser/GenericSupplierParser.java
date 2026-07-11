package org.example.backendbvaberiaperfumes.service.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.backendbvaberiaperfumes.dto.ColumnMapping;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parser universal para el Excel de CUALQUIER proveedor. No se registra por nombre:
 * es el fallback cuando no hay un parser afinado (Zimaxx/Magnet). Auto-detecta el header
 * y las columnas (UPC / descripcion / precio / marca / estado) por palabras clave; el admin
 * puede corregir el mapeo desde la vista previa. Todo se cruza luego por UPC/GTIN.
 */
@Component
public class GenericSupplierParser extends AbstractSupplierParser {

    /** Nombre centinela: no coincide con ningun Supplier real. */
    public static final String SENTINEL = "__generic__";

    private static final List<String> UPC_ALIASES = List.of(
            "upc", "codigo", "código", "code", "barcode", "ean", "gtin", "sku", "cod. barras");
    private static final List<String> DESC_ALIASES = List.of(
            "sales description", "title product", "description", "descripción", "descripcion",
            "title", "nombre", "producto", "product", "item", "detalle", "articulo", "artículo");
    private static final List<String> BRAND_ALIASES = List.of("brand", "marca");
    // Precios: preferir el neto/final antes que la lista.
    private static final List<String> PRICE_PREFERRED = List.of("final", "neto", "net", "precio final");
    private static final List<String> PRICE_ALIASES = List.of(
            "sales price", "unit price", "precio unitario", "price", "cost", "precio", "costo", "wholesale", "valor");
    private static final List<String> STATUS_ALIASES = List.of(
            "availability", "disponibilidad", "status", "estado", "type", "stock", "disponible");

    @Override
    public String supplierName() { return SENTINEL; }

    @Override
    public List<ParsedRow> parse(InputStream is) throws Exception {
        return detect(is.readAllBytes(), null).rows;
    }

    /** Resultado enriquecido: filas + mapeo usado + nombres de columnas (para la UI). */
    public static class Detected {
        public List<ParsedRow> rows = new ArrayList<>();
        public ColumnMapping mapping;
        public List<String> headers = new ArrayList<>();
    }

    /** Parsea con auto-deteccion, salvo que se pase un override con las columnas ya elegidas. */
    public Detected detect(byte[] bytes, ColumnMapping override) throws Exception {
        Detected d = new Detected();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            ColumnMapping map = (override != null && override.descriptionCol != null) ? override : autoDetect(sheet);
            d.mapping = map;
            d.headers = headerNames(sheet, map.headerRow);
            d.rows = readRows(sheet, map);
        }
        return d;
    }

    private ColumnMapping autoDetect(Sheet sheet) {
        ColumnMapping m = new ColumnMapping();
        int lastScan = Math.min(25, sheet.getLastRowNum());
        for (int r = 0; r <= lastScan; r++) {
            Map<String, Integer> h = headerMap(sheet.getRow(r));
            if (h.isEmpty()) continue;
            int desc = findCol(h, DESC_ALIASES);
            int upc = findCol(h, UPC_ALIASES);
            int price = findCol(h, PRICE_PREFERRED);
            if (price < 0) price = findCol(h, PRICE_ALIASES);
            if (desc >= 0 && (price >= 0 || upc >= 0)) {
                m.headerRow = r;
                m.descriptionCol = desc;
                m.upcCol = upc >= 0 ? upc : null;
                m.priceCol = price >= 0 ? price : null;
                int brand = findCol(h, BRAND_ALIASES);
                m.brandCol = brand >= 0 ? brand : null;
                int status = findCol(h, STATUS_ALIASES);
                m.statusCol = status >= 0 ? status : null;
                return m;
            }
        }
        // Sin header reconocible: asumir header en fila 0 y columnas por posicion.
        m.headerRow = 0;
        m.descriptionCol = 0;
        m.upcCol = 1;
        m.priceCol = 2;
        return m;
    }

    private List<ParsedRow> readRows(Sheet sheet, ColumnMapping m) {
        List<ParsedRow> out = new ArrayList<>();
        int start = (m.headerRow == null ? 0 : m.headerRow) + 1;
        int blanks = 0;
        for (int r = start; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (rowEmpty(row)) { if (++blanks > 8) break; else continue; }
            blanks = 0;

            String desc = m.descriptionCol != null ? cellStr(row, m.descriptionCol) : null;
            if (desc == null || desc.isBlank()) continue;

            String upcRaw = m.upcCol != null ? cellRawId(row, m.upcCol) : null;
            Double price = m.priceCol != null ? cellNum(row, m.priceCol) : null;
            String priceStr = m.priceCol != null ? cellStr(row, m.priceCol) : null;
            String brandCol = m.brandCol != null ? cellStr(row, m.brandCol) : null;

            boolean noUpc = upcRaw == null || upcRaw.isBlank();
            // Sin UPC y sin precio -> fila separador/basura (NO es un producto ni una marca). Se ignora.
            // (No se hereda marca por seccion: eso metia el nombre del proveedor como marca.)
            if (noUpc && price == null) continue;

            ParsedRow pr = new ParsedRow();
            pr.gtin = PerfumeNormalizer.gtin14(upcRaw);
            // Marca: columna de marca si existe; si no, la primera palabra del titulo. Nunca el proveedor.
            String brand = (brandCol != null && !brandCol.isBlank()) ? brandCol.trim() : firstWord(desc);
            pr.brand = brand;
            pr.rawTitle = desc;
            // El nombre nunca lleva la marca al inicio.
            pr.name = PerfumeNormalizer.cleanName(PerfumeNormalizer.stripBrandPrefix(brand, desc));
            pr.ml = sizeFromText(desc, m.sizeUnit);
            pr.forma = PerfumeNormalizer.detectForma(desc);
            pr.costUsd = price;
            pr.supplierSku = upcRaw;
            pr.inStock = detectStock(row, m, price, priceStr);
            pr.flashSale = false;
            out.add(pr);
        }
        return out;
    }

    private boolean detectStock(Row row, ColumnMapping m, Double price, String priceStr) {
        if (m.statusCol != null) {
            String s = cellStr(row, m.statusCol);
            if (s != null && !s.isBlank()) {
                String lc = PerfumeNormalizer.stripAccents(s).toLowerCase();
                if (lc.contains("sold") || lc.contains("out") || lc.contains("agot") || lc.contains("no dispon"))
                    return false;
                if (lc.contains("avail") || lc.contains("dispon") || lc.contains("stock") || lc.contains("flash"))
                    return true;
            }
        }
        // Celda de precio no numerica (ej. "SOLD") -> fuera de stock.
        if (price == null && priceStr != null && !priceStr.isBlank()) {
            String lc = PerfumeNormalizer.stripAccents(priceStr).toLowerCase();
            if (lc.contains("sold") || lc.contains("out") || lc.contains("agot")) return false;
        }
        // Sin precio no se puede comprar.
        if (price == null) return false;
        return true;
    }

    private Integer sizeFromText(String text, String unit) {
        if ("ml".equalsIgnoreCase(unit)) return PerfumeNormalizer.mlFromMl(text);
        if ("oz".equalsIgnoreCase(unit)) return PerfumeNormalizer.mlFromOz(text);
        Integer ml = PerfumeNormalizer.mlFromMl(text);
        if (ml == null) ml = PerfumeNormalizer.mlFromOz(text);
        return ml;
    }

    private String firstWord(String s) {
        if (s == null) return "Generico";
        String t = s.trim();
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    private int findCol(Map<String, Integer> headers, List<String> aliases) {
        for (String a : aliases) {
            Integer idx = headers.get(a);
            if (idx != null) return idx;
        }
        for (Map.Entry<String, Integer> e : headers.entrySet()) {
            for (String a : aliases) {
                if (e.getKey().equals(a) || e.getKey().contains(a)) return e.getValue();
            }
        }
        return -1;
    }

    private List<String> headerNames(Sheet sheet, Integer headerRow) {
        List<String> out = new ArrayList<>();
        Row h = sheet.getRow(headerRow == null ? 0 : headerRow);
        if (h == null) return out;
        for (int i = 0; i < h.getLastCellNum(); i++) {
            String s = cellStr(h.getCell(i));
            out.add(s == null || s.isBlank() ? ("Columna " + (i + 1)) : s);
        }
        return out;
    }
}
