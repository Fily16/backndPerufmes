package org.example.backendbvaberiaperfumes.service.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Layout Zimaxx ("Price List"): filas 0-6 son basura; el header real tiene
 * UPC | Sku | Brand | Title Product | Price | Type | Qty | Total.
 * Tamano en onzas dentro del titulo; Type = Available / Flash Sale.
 */
@Component
public class ZimaxxParser extends AbstractSupplierParser {

    @Override
    public String supplierName() { return "Zimaxx"; }

    @Override
    public List<ParsedRow> parse(InputStream is) throws Exception {
        List<ParsedRow> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(is)) {
            Sheet sheet = wb.getSheetAt(0);

            // 1. localizar la fila de header (la que contiene UPC + Brand + Title)
            int headerIdx = -1;
            Map<String, Integer> headers = null;
            for (int r = 0; r <= Math.min(20, sheet.getLastRowNum()); r++) {
                Map<String, Integer> h = headerMap(sheet.getRow(r));
                if (h.containsKey("upc") && h.containsKey("brand")
                        && (h.containsKey("title product") || h.containsKey("title"))) {
                    headerIdx = r; headers = h; break;
                }
            }
            if (headers == null) throw new IllegalArgumentException("No se encontro el header (UPC/Brand/Title) en el Excel de Zimaxx");

            int cUpc = col(headers, "upc");
            int cSku = col(headers, "sku");
            int cBrand = col(headers, "brand");
            int cTitle = col(headers, "title product", "title");
            int cPrice = col(headers, "price");
            int cType = col(headers, "type");

            // 2. leer filas de datos
            int blanks = 0;
            for (int r = headerIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (rowEmpty(row)) { if (++blanks > 5) break; else continue; }
                blanks = 0;

                String title = cellStr(row, cTitle);
                Double price = cellNum(row, cPrice);
                if (title == null || title.isBlank() || price == null) continue;

                String type = cellStr(row, cType);
                String typeLc = type == null ? "" : type.toLowerCase();

                ParsedRow pr = new ParsedRow();
                applyGtin(pr, cellRawId(row, cUpc));
                pr.brand = cellStr(row, cBrand);
                pr.rawTitle = title;
                pr.name = PerfumeNormalizer.cleanName(title);
                pr.ml = PerfumeNormalizer.mlFromOz(title);
                pr.forma = PerfumeNormalizer.detectForma(title);
                pr.costUsd = price;
                pr.flashSale = typeLc.contains("flash");
                pr.inStock = typeLc.contains("available") || pr.flashSale;
                pr.supplierSku = cellStr(row, cSku);
                out.add(pr);
            }
        }
        return out;
    }
}
