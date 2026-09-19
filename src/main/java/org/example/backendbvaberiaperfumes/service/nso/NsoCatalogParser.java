package org.example.backendbvaberiaperfumes.service.nso;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lector del catalogo NSO que sube la admin (xlsx o csv). Sin BD: devuelve registros listos para el upsert,
 * los links de investigacion y las filas que no se pudieron leer, con el motivo en espanol.
 *
 * Se detecta por ENCABEZADOS, no por nombre de hoja ni posicion de columnas:
 *  - Hoja catalogo:      NSO + Marca + Producto declarado (o "Producto"). Columnas opcionales: Titular, RUC, Tipo,
 *                        Categoria, Origen, Fuente, EAN, Series, Kg, FOB, US$/kg, Ultima importacion. "Ano NSO"
 *                        se ignora (el anio se calcula desde el codigo).
 *  - Hoja investigacion: NSO + "Producto (tu lista)" + SKU (+ Estado, Marca, Producto en la NSO, Titular,
 *                        RUC titular, Fuente, UPC, Precio USD, Lista). Tiene falsos positivos probados: sus links
 *                        solo sirven como evidencia (alias origin=RESEARCH), NUNCA asignan automatico.
 *  - Otras hojas ("Marca con NSO - falta prod", "Sin NSO por marca") se ignoran.
 *  - CSV: header nso,categoria,marca,producto,ean,titular,ruc,origen,series,kg,fob,usd_kg,ultima,anio_nso
 *    (UTF-8 con BOM, CRLF, comillas). Parser propio; acepta tambien ';' como separador.
 */
@Component
public class NsoCatalogParser {

    public static final String FILE_XLSX = "XLSX";
    public static final String FILE_CSV = "CSV";

    /** Cuantas filas del inicio de cada hoja se revisan buscando el encabezado. */
    private static final int HEADER_SCAN_ROWS = 15;
    private static final int RAW_MAX = 300;

    // Largo de las columnas de nso_records (NsoRecord): una celda mas larga NO puede tumbar la carga entera
    // (un solo "value too long" revierte las ~1,700 filas). Identidad (marca, nombre) larga -> fila invalida con
    // motivo; datos informativos (titular, tipo, categoria, origen) -> se recortan; RUC -> se limpia o se omite.
    static final int MAX_BRAND = 150;
    static final int MAX_DECLARED = 500;
    static final int MAX_TITULAR = 300;
    static final int MAX_RUC = 20;
    static final int MAX_TIPO = 40;
    static final int MAX_ORIGEN = 80;
    private static final Pattern RUC_11 = Pattern.compile("(?<!\\d)\\d{11}(?!\\d)");
    /** Espacio duro (celdas copiadas de la web). */
    private static final char NBSP = (char) 0xA0;
    /** Marca de orden de bytes UTF-8 que Excel pone al inicio del CSV. */
    private static final char BOM = (char) 0xFEFF;

    // --- nombres de columna aceptados (ya normalizados con normHeader) ---
    private static final String[] H_NSO = {"nso", "codigo nso", "cod nso", "nso codigo", "notificacion sanitaria", "notificacion sanitaria obligatoria"};
    private static final String[] H_MARCA = {"marca", "brand"};
    private static final String[] H_PRODUCTO = {"producto declarado", "producto"};
    private static final String[] H_TITULAR = {"titular", "titular de la nso"};
    private static final String[] H_RUC = {"ruc", "ruc titular"};
    private static final String[] H_TIPO = {"tipo"};
    private static final String[] H_CATEGORIA = {"categoria"};
    private static final String[] H_ORIGEN = {"origen", "pais origen", "pais de origen"};
    private static final String[] H_FUENTE = {"fuente"};
    private static final String[] H_EAN = {"ean", "upc", "gtin"};
    private static final String[] H_SERIES = {"series"};
    private static final String[] H_KG = {"kg"};
    private static final String[] H_FOB = {"fob", "fob usd"};
    private static final String[] H_USD_KG = {"usd kg", "us kg", "us kg usd"};
    private static final String[] H_ULTIMA = {"ultima", "ultima importacion"};

    private static final String[] H_TU_LISTA = {"producto tu lista"};
    private static final String[] H_EN_NSO = {"producto en la nso"};
    private static final String[] H_SKU = {"sku"};
    private static final String[] H_UPC = {"upc", "ean", "gtin"};
    private static final String[] H_ESTADO = {"estado"};
    private static final String[] H_PRECIO = {"precio usd", "precio"};
    private static final String[] H_LISTA = {"lista", "proveedor"};

    // ===================== resultado =====================

    /** Resultado del parseo. Listas mutables para que el servicio pueda consumirlas. */
    public static final class ParsedCatalog {
        /** "XLSX" | "CSV" */
        public final String fileType;
        /** Registros validos, uno por codigo (el primero que aparece gana), en orden del archivo. */
        public final List<NsoRecord> records = new ArrayList<>();
        /** Links de la hoja de investigacion (vacia en CSV). */
        public final List<ResearchLink> researchLinks = new ArrayList<>();
        /** Filas que no se pudieron usar, con motivo en espanol. */
        public final List<InvalidRow> invalidRows = new ArrayList<>();

        public ParsedCatalog(String fileType) {
            this.fileType = fileType;
        }

        public String getFileType() { return fileType; }
        public List<NsoRecord> getRecords() { return records; }
        public List<ResearchLink> getResearchLinks() { return researchLinks; }
        public List<InvalidRow> getInvalidRows() { return invalidRows; }

        /**
         * Alias origin=RESEARCH de todos los links, sin repetir (kind, aliasKey, nsoCode):
         * SUPPLIER_SKU "listaNormalizada|SKU" y GTIN si el UPC valida. Listos para guardar tras
         * aliasRepo.deleteByOrigin(RESEARCH). NUNCA deben asignar automatico.
         */
        public List<NsoAlias> researchAliases() {
            Map<String, NsoAlias> out = new LinkedHashMap<>();
            for (ResearchLink l : researchLinks) {
                for (NsoAlias a : l.toAliases()) {
                    out.putIfAbsent(a.getKind() + "#" + a.getAliasKey() + "#" + a.getCodeKey(), a);
                }
            }
            return new ArrayList<>(out.values());
        }
    }

    /** Fila que no se pudo leer: numero de fila como lo ve la usuaria (1 = encabezado), texto crudo y motivo. */
    public static final class InvalidRow {
        public final int row;
        public final String raw;
        public final String reason;

        public InvalidRow(int row, String raw, String reason) {
            this.row = row;
            this.raw = raw;
            this.reason = reason;
        }

        public int getRow() { return row; }
        public String getRaw() { return raw; }
        public String getReason() { return reason; }
    }

    /** Link de la investigacion: "este producto de tu lista tendria este NSO" (ALTA/MEDIA). */
    public static final class ResearchLink {
        public int row;
        public String code;
        /** ALTA | MEDIA (confianza de la investigacion). */
        public String estado;
        public String brand;
        /** Producto tal como esta en la lista del proveedor. */
        public String productName;
        /** Producto como figura en la NSO. */
        public String declaredName;
        public String titular;
        public String ruc;
        public String fuente;
        public String sku;
        public String upcRaw;
        /** GTIN-14 validado del UPC, o null. */
        public String gtin;
        public Double priceUsd;
        /** Lista/proveedor tal como viene (ej. "Zimaxx"). */
        public String list;
        /** NsoKeys.skuKey(list, sku) o null si falta alguno. */
        public String skuAliasKey;

        public String getCode() { return code; }
        public String getEstado() { return estado; }
        public String getBrand() { return brand; }
        public String getProductName() { return productName; }
        public String getDeclaredName() { return declaredName; }
        public String getSku() { return sku; }
        public String getGtin() { return gtin; }
        public String getList() { return list; }
        public String getSkuAliasKey() { return skuAliasKey; }

        /** Alias RESEARCH de este link: SUPPLIER_SKU (si hay lista+SKU) y GTIN (si el UPC valida). */
        public List<NsoAlias> toAliases() {
            List<NsoAlias> out = new ArrayList<>();
            String detail = trimTo((estado == null ? "" : estado + " · ") + (productName == null ? "" : productName), 500);
            if (skuAliasKey != null) {
                NsoAlias a = new NsoAlias(NsoAlias.KIND_SUPPLIER_SKU, skuAliasKey, code, NsoAlias.POSITIVE, NsoAlias.ORIGIN_RESEARCH);
                a.setDetail(detail);
                out.add(a);
            }
            if (gtin != null) {
                NsoAlias a = new NsoAlias(NsoAlias.KIND_GTIN, gtin, code, NsoAlias.POSITIVE, NsoAlias.ORIGIN_RESEARCH);
                a.setDetail(detail);
                out.add(a);
            }
            return out;
        }
    }

    // ===================== entrada =====================

    /** Parsea usando el anio del reloj para calcular nsoYear. */
    public ParsedCatalog parse(String filename, byte[] bytes) {
        return parse(filename, bytes, Year.now().getValue());
    }

    /**
     * Detecta el tipo (por contenido y extension) y parsea.
     * @throws IllegalArgumentException con mensaje claro en espanol si el archivo no se reconoce.
     */
    public ParsedCatalog parse(String filename, byte[] bytes, int currentYear) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT).trim();
        boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        boolean ole2 = bytes.length >= 4 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0;
        if (zip || ole2 || name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return parseWorkbook(bytes, currentYear);
        }
        return parseCsv(bytes, currentYear);
    }

    // ===================== XLSX =====================

    private ParsedCatalog parseWorkbook(byte[] bytes, int currentYear) {
        try (Workbook wb = openWorkbook(bytes)) {
            ParsedCatalog out = new ParsedCatalog(FILE_XLSX);
            Set<String> seen = new LinkedHashSet<>();
            boolean catalogFound = false;
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                int first = Math.max(0, sheet.getFirstRowNum());
                int last = Math.min(sheet.getLastRowNum(), first + HEADER_SCAN_ROWS - 1);
                for (int r = first; r <= last; r++) {
                    Row header = sheet.getRow(r);
                    if (header == null) continue;
                    Map<String, Integer> h = headerMap(header);
                    if (isResearch(h)) {
                        readResearchSheet(sheet, r, h, out);
                        break;
                    }
                    if (isCatalog(h)) {
                        catalogFound = true;
                        readCatalogSheet(sheet, r, h, out, seen, currentYear);
                        break;
                    }
                }
            }
            if (!catalogFound) {
                throw new IllegalArgumentException("No reconocimos el archivo: ninguna hoja tiene las columnas "
                        + "NSO, Marca y Producto declarado. Sube la lista de NSO (catálogo) en .xlsx o .csv.");
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("No se pudo leer el Excel: " + e.getMessage());
        }
    }

    private void readCatalogSheet(Sheet sheet, int headerRow, Map<String, Integer> h,
                                  ParsedCatalog out, Set<String> seen, int currentYear) {
        int cNso = col(h, H_NSO), cMarca = col(h, H_MARCA), cProd = col(h, H_PRODUCTO);
        int cTit = col(h, H_TITULAR), cRuc = col(h, H_RUC), cTipo = col(h, H_TIPO), cCat = col(h, H_CATEGORIA);
        int cOri = col(h, H_ORIGEN), cFue = col(h, H_FUENTE), cEan = col(h, H_EAN), cSer = col(h, H_SERIES);
        int cKg = col(h, H_KG), cFob = col(h, H_FOB), cUsdKg = col(h, H_USD_KG), cUlt = col(h, H_ULTIMA);
        String sheetName = sheet.getSheetName();
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (rowEmpty(row)) continue;
            Map<String, String> v = new HashMap<>();
            v.put("nso", text(row, cNso));
            v.put("marca", text(row, cMarca));
            v.put("producto", text(row, cProd));
            v.put("titular", text(row, cTit));
            v.put("ruc", idText(row, cRuc));
            v.put("tipo", text(row, cTipo));
            v.put("categoria", text(row, cCat));
            v.put("origen", text(row, cOri));
            v.put("fuente", text(row, cFue));
            v.put("ean", idText(row, cEan));
            v.put("series", text(row, cSer));
            v.put("kg", text(row, cKg));
            v.put("fob", text(row, cFob));
            v.put("usd_kg", text(row, cUsdKg));
            v.put("ultima", dateText(row, cUlt));
            String raw = "[" + sheetName + "] " + rowRaw(row);
            addRecord(out, seen, r + 1, raw, v, FILE_XLSX, currentYear, " (hoja " + sheetName + ")");
        }
    }

    private void readResearchSheet(Sheet sheet, int headerRow, Map<String, Integer> h, ParsedCatalog out) {
        int cNso = col(h, H_NSO), cTuLista = col(h, H_TU_LISTA), cSku = col(h, H_SKU);
        int cEstado = col(h, H_ESTADO), cMarca = col(h, H_MARCA), cEnNso = col(h, H_EN_NSO);
        int cTit = col(h, H_TITULAR), cRuc = col(h, H_RUC), cFue = col(h, H_FUENTE), cUpc = col(h, H_UPC);
        int cPrecio = col(h, H_PRECIO), cLista = col(h, H_LISTA);
        String sheetName = sheet.getSheetName();
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (rowEmpty(row)) continue;
            int rowNum = r + 1;
            String raw = "[" + sheetName + "] " + rowRaw(row);
            String codeRaw = text(row, cNso);
            String suffix = " (hoja " + sheetName + ")";
            if (codeRaw == null) {
                out.invalidRows.add(new InvalidRow(rowNum, raw, "Falta el código NSO" + suffix));
                continue;
            }
            String code = NsoCode.canonicalize(codeRaw);
            if (code == null) {
                out.invalidRows.add(new InvalidRow(rowNum, raw, "Código NSO con formato inválido «" + codeRaw + "»" + suffix));
                continue;
            }
            ResearchLink l = new ResearchLink();
            l.row = rowNum;
            l.code = code;
            l.estado = upperOrNull(text(row, cEstado));
            l.brand = text(row, cMarca);
            l.productName = text(row, cTuLista);
            l.declaredName = text(row, cEnNso);
            l.titular = text(row, cTit);
            l.ruc = idText(row, cRuc);
            l.fuente = text(row, cFue);
            l.sku = text(row, cSku);
            l.upcRaw = idText(row, cUpc);
            l.gtin = l.upcRaw == null ? null : NsoKeys.gtinKey(l.upcRaw);
            l.priceUsd = number(text(row, cPrecio));
            l.list = text(row, cLista);
            l.skuAliasKey = NsoKeys.skuKey(l.list, l.sku);
            if (l.skuAliasKey == null && l.gtin == null) {
                String why = l.sku == null ? "Falta el SKU" : "Falta la lista (proveedor)";
                out.invalidRows.add(new InvalidRow(rowNum, raw,
                        why + " y no hay un UPC válido: no se puede relacionar con tu lista" + suffix));
                continue;
            }
            out.researchLinks.add(l);
        }
    }

    private static boolean isCatalog(Map<String, Integer> h) {
        return col(h, H_NSO) >= 0 && col(h, H_MARCA) >= 0 && col(h, H_PRODUCTO) >= 0 && col(h, H_TU_LISTA) < 0;
    }

    private static boolean isResearch(Map<String, Integer> h) {
        return col(h, H_NSO) >= 0 && col(h, H_TU_LISTA) >= 0 && col(h, H_SKU) >= 0;
    }

    private Map<String, Integer> headerMap(Row header) {
        Map<String, Integer> map = new HashMap<>();
        if (header.getLastCellNum() < 0) return map;
        for (int i = Math.max(0, header.getFirstCellNum()); i < header.getLastCellNum(); i++) {
            String s = text(header, i);
            if (s != null) map.putIfAbsent(normHeader(s), i);
        }
        return map;
    }

    /** Texto de una celda (trim; vacio -> null). Numeros sin notacion cientifica ni ".0". */
    private static String text(Row row, int col) {
        if (row == null || col < 0) return null;
        Cell c = row.getCell(col);
        if (c == null) return null;
        String s;
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        switch (t) {
            case STRING -> s = c.getStringCellValue();
            case NUMERIC -> s = DateUtil.isCellDateFormatted(c)
                    ? fmtDate(c)
                    : new BigDecimal(String.valueOf(c.getNumericCellValue())).stripTrailingZeros().toPlainString();
            case BOOLEAN -> s = String.valueOf(c.getBooleanCellValue());
            default -> s = null;
        }
        if (s == null) return null;
        s = s.replace(NBSP, ' ').trim();
        return s.isEmpty() ? null : s;
    }

    /** Identificadores (RUC, UPC) como digitos exactos: un numero de Excel no debe salir en notacion cientifica. */
    private static String idText(Row row, int col) {
        if (row == null || col < 0) return null;
        Cell c = row.getCell(col);
        if (c == null) return null;
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (t == CellType.NUMERIC && !DateUtil.isCellDateFormatted(c)) {
            return new BigDecimal(String.valueOf(c.getNumericCellValue())).toBigInteger().toString();
        }
        return text(row, col);
    }

    /** Fecha como DD/MM/YYYY tanto si la celda es fecha real de Excel como si es texto. */
    private static String dateText(Row row, int col) {
        return text(row, col);
    }

    private static String fmtDate(Cell c) {
        LocalDate d = c.getLocalDateTimeCellValue().toLocalDate();
        return String.format("%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    private static Workbook openWorkbook(byte[] bytes) {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("No se pudo abrir el Excel. Verifica que sea un archivo .xlsx válido.");
        }
    }

    private static boolean rowEmpty(Row row) {
        if (row == null || row.getLastCellNum() < 0) return true;
        for (int i = Math.max(0, row.getFirstCellNum()); i < row.getLastCellNum(); i++) {
            if (text(row, i) != null) return false;
        }
        return true;
    }

    private static String rowRaw(Row row) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            String s = text(row, i);
            parts.add(s == null ? "" : s);
        }
        while (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) parts.remove(parts.size() - 1);
        return trimTo(String.join(" | ", parts), RAW_MAX);
    }

    // ===================== CSV =====================

    private ParsedCatalog parseCsv(byte[] bytes, int currentYear) {
        String content = decode(bytes);
        if (!content.isEmpty() && content.charAt(0) == BOM) content = content.substring(1);
        if (content.isBlank()) throw new IllegalArgumentException("El archivo está vacío.");

        String firstLine = content.lines().findFirst().orElse("");
        char sep = count(firstLine, ';') > count(firstLine, ',') ? ';' : ',';
        List<CsvLine> lines = splitCsv(content, sep);
        if (lines.isEmpty()) throw new IllegalArgumentException("El archivo está vacío.");

        List<String> header = lines.get(0).fields;
        Map<String, Integer> h = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String n = normHeader(header.get(i));
            if (!n.isEmpty()) h.putIfAbsent(n, i);
        }
        if (!isCatalog(h)) {
            throw new IllegalArgumentException("No reconocimos el archivo: el CSV debe tener las columnas "
                    + "nso, marca y producto en la primera fila.");
        }
        int cNso = col(h, H_NSO), cMarca = col(h, H_MARCA), cProd = col(h, H_PRODUCTO);
        int cTit = col(h, H_TITULAR), cRuc = col(h, H_RUC), cTipo = col(h, H_TIPO), cCat = col(h, H_CATEGORIA);
        int cOri = col(h, H_ORIGEN), cFue = col(h, H_FUENTE), cEan = col(h, H_EAN), cSer = col(h, H_SERIES);
        int cKg = col(h, H_KG), cFob = col(h, H_FOB), cUsdKg = col(h, H_USD_KG), cUlt = col(h, H_ULTIMA);

        ParsedCatalog out = new ParsedCatalog(FILE_CSV);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            CsvLine line = lines.get(i);
            List<String> f = line.fields;
            boolean blank = f.stream().allMatch(x -> x == null || x.isBlank());
            if (blank) continue;
            String raw = trimTo(line.raw, RAW_MAX);
            if (f.size() != header.size()) {
                out.invalidRows.add(new InvalidRow(line.lineNumber, raw,
                        "La fila tiene " + f.size() + " columnas y el encabezado " + header.size()
                                + ": revisa comas o comillas en esa fila"));
                continue;
            }
            Map<String, String> v = new HashMap<>();
            v.put("nso", field(f, cNso));
            v.put("marca", field(f, cMarca));
            v.put("producto", field(f, cProd));
            v.put("titular", field(f, cTit));
            v.put("ruc", field(f, cRuc));
            v.put("tipo", field(f, cTipo));
            v.put("categoria", field(f, cCat));
            v.put("origen", field(f, cOri));
            v.put("fuente", field(f, cFue));
            v.put("ean", field(f, cEan));
            v.put("series", field(f, cSer));
            v.put("kg", field(f, cKg));
            v.put("fob", field(f, cFob));
            v.put("usd_kg", field(f, cUsdKg));
            v.put("ultima", field(f, cUlt));
            addRecord(out, seen, line.lineNumber, raw, v, FILE_CSV, currentYear, "");
        }
        return out;
    }

    private static final class CsvLine {
        final int lineNumber;
        final List<String> fields;
        final String raw;

        CsvLine(int lineNumber, List<String> fields, String raw) {
            this.lineNumber = lineNumber;
            this.fields = fields;
            this.raw = raw;
        }
    }

    /**
     * Separa el CSV en registros respetando comillas ("" = comilla literal) y saltos de linea dentro de
     * comillas. Acepta CRLF, LF y CR. lineNumber = linea fisica donde empieza el registro (1 = encabezado).
     */
    static List<CsvLine> splitCsv(String content, char sep) {
        List<CsvLine> out = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        int line = 1;
        int recordStart = 1;
        int n = content.length();
        for (int i = 0; i < n; i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        cur.append('"');
                        raw.append("\"\"");
                        i++;
                    } else {
                        inQuotes = false;
                        raw.append('"');
                    }
                } else {
                    if (c == '\n' || (c == '\r' && !(i + 1 < n && content.charAt(i + 1) == '\n'))) line++;
                    cur.append(c);
                    raw.append(c);
                }
                continue;
            }
            if (c == '"' && !fieldStarted) {
                inQuotes = true;
                fieldStarted = true;
                raw.append(c);
            } else if (c == sep) {
                fields.add(cur.toString());
                cur.setLength(0);
                fieldStarted = false;
                raw.append(c);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n') i++;
                fields.add(cur.toString());
                out.add(new CsvLine(recordStart, fields, raw.toString()));
                fields = new ArrayList<>();
                cur.setLength(0);
                raw.setLength(0);
                fieldStarted = false;
                line++;
                recordStart = line;
            } else {
                cur.append(c);
                raw.append(c);
                fieldStarted = true;
            }
        }
        if (cur.length() > 0 || !fields.isEmpty() || fieldStarted) {
            fields.add(cur.toString());
            out.add(new CsvLine(recordStart, fields, raw.toString()));
        }
        return out;
    }

    /** UTF-8 estricto; si el archivo no es UTF-8 valido (CSV guardado por Excel en Windows) se lee como Windows-1252. */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    private static String field(List<String> f, int col) {
        if (col < 0 || col >= f.size()) return null;
        String s = f.get(col);
        if (s == null) return null;
        s = s.replace(NBSP, ' ').trim();
        return s.isEmpty() ? null : s;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    // ===================== comun =====================

    /** Valida una fila de catalogo y la agrega como NsoRecord (o como fila invalida con motivo). */
    private void addRecord(ParsedCatalog out, Set<String> seen, int rowNum, String raw, Map<String, String> v,
                           String fileType, int currentYear, String suffix) {
        String codeRaw = v.get("nso");
        if (codeRaw == null) {
            out.invalidRows.add(new InvalidRow(rowNum, raw, "Falta el código NSO" + suffix));
            return;
        }
        String code = NsoCode.canonicalize(codeRaw);
        if (code == null) {
            out.invalidRows.add(new InvalidRow(rowNum, raw, "Código NSO con formato inválido «" + codeRaw + "»" + suffix));
            return;
        }
        String brand = v.get("marca");
        if (brand == null || NsoKeys.brandKey(brand).isEmpty()) {
            out.invalidRows.add(new InvalidRow(rowNum, raw, "Falta la marca del código " + code + suffix));
            return;
        }
        String declared = v.get("producto");
        if (declared == null) {
            out.invalidRows.add(new InvalidRow(rowNum, raw, "Falta el producto declarado del código " + code + suffix));
            return;
        }
        if (brand.length() > MAX_BRAND || NsoKeys.brandKey(brand).length() > MAX_BRAND) {
            out.invalidRows.add(new InvalidRow(rowNum, raw, "La marca del código " + code + " es demasiado larga (máximo "
                    + MAX_BRAND + " letras): revisa esa celda" + suffix));
            return;
        }
        if (declared.length() > MAX_DECLARED) {
            out.invalidRows.add(new InvalidRow(rowNum, raw, "El producto declarado del código " + code
                    + " es demasiado largo (máximo " + MAX_DECLARED + " letras): revisa esa celda" + suffix));
            return;
        }
        if (!seen.add(code)) {
            out.invalidRows.add(new InvalidRow(rowNum, raw, "El código " + code + " está repetido en el archivo: se usó la primera fila" + suffix));
            return;
        }

        NsoRecord rec = new NsoRecord(code, brand, NsoKeys.brandKey(brand), declared);
        rec.setTitular(trimTo(v.get("titular"), MAX_TITULAR));
        rec.setRuc(cleanRuc(v.get("ruc")));
        String tipo = v.get("tipo") != null ? v.get("tipo") : v.get("categoria");
        String categoria = v.get("categoria") != null ? v.get("categoria") : v.get("tipo");
        rec.setTipo(trimTo(tipo, MAX_TIPO));
        rec.setCategoria(trimTo(categoria, MAX_TIPO));
        rec.setOrigen(trimTo(v.get("origen"), MAX_ORIGEN));
        String ean = v.get("ean");
        rec.setEan(ean == null ? null : NsoKeys.gtinKey(ean));
        Double series = number(v.get("series"));
        rec.setSeries(series == null ? null : (int) Math.round(series));
        rec.setKg(number(v.get("kg")));
        rec.setFobUsd(number(v.get("fob")));
        rec.setUsdKg(number(v.get("usd_kg")));
        rec.setLastImportDate(NsoKeys.normalizeDate(v.get("ultima")));
        rec.setNsoYear(NsoCode.year(code, currentYear));
        rec.setCountry(NsoCode.country(code));
        String fuente = v.get("fuente");
        if (fuente != null && fuente.toLowerCase(Locale.ROOT).contains("aduanet")) {
            rec.setSource(NsoRecord.SOURCE_ADUANET);
        } else {
            rec.setSource(FILE_XLSX.equals(fileType) ? NsoRecord.SOURCE_XLSX : NsoRecord.SOURCE_CSV);
        }
        rec.setActive(true);
        rec.setInLastUpload(true);
        out.records.add(rec);
    }

    /**
     * RUC como texto: solo digitos si el valor es numerico (quita ".0" o espacios); si no, el texto tal cual.
     * Si no cabe en la columna (20): el primer RUC de 11 digitos que traiga ("20601234567 / 20509876543" -> el
     * primero) o, si no trae ninguno, sin RUC. El codigo NSO se carga igual: un RUC mal escrito no lo invalida.
     */
    static String cleanRuc(String ruc) {
        if (ruc == null) return null;
        String s = ruc.trim();
        if (s.isEmpty()) return null;
        if (s.matches("\\d+(\\.0+)?")) s = s.replaceAll("\\.0+$", "");
        else if (s.matches("\\d{1,3}(\\s\\d{2,4})+")) s = s.replace(" ", "");
        if (s.length() <= MAX_RUC) return s;
        Matcher m = RUC_11.matcher(s);
        return m.find() ? m.group() : null;
    }

    /** Numero tolerante: "1057.82", "1,20", "1.234,5", "US$ 25.8". Null si no se puede leer. */
    static Double number(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[^0-9,.\\-]", "");
        if (t.isEmpty() || t.equals("-")) return null;
        int lastComma = t.lastIndexOf(',');
        int lastDot = t.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) t = t.replace(".", "").replace(',', '.');
            else t = t.replace(",", "");
        } else if (lastComma >= 0) {
            t = t.replace(',', '.');
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Encabezado normalizado: sin acentos, minusculas, todo lo que no es [a-z0-9] -> espacio. "US$/kg" -> "us kg". */
    static String normHeader(String s) {
        return NsoKeys.fold(s == null ? "" : s.replace("&", " "));
    }

    private static int col(Map<String, Integer> h, String[] names) {
        for (String n : names) {
            Integer idx = h.get(n);
            if (idx != null) return idx;
        }
        return -1;
    }

    private static String upperOrNull(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
