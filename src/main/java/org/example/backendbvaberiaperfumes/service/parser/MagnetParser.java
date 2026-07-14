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
 * Layout Magnet ("Worksheet"): SKU | Sales Description | Sales Price.
 * La marca viene como fila-seccion (sin SKU ni precio) y de prefijo en la descripcion.
 * Tamano en ML; sin columna de disponibilidad (se asume en stock).
 */
@Component
public class MagnetParser extends AbstractSupplierParser {

    @Override
    public String supplierName() { return "Magnet"; }

    @Override
    public List<ParsedRow> parse(InputStream is) throws Exception {
        List<ParsedRow> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(is)) {
            Sheet sheet = wb.getSheetAt(0);

            int headerIdx = -1;
            Map<String, Integer> headers = null;
            for (int r = 0; r <= Math.min(10, sheet.getLastRowNum()); r++) {
                Map<String, Integer> h = headerMap(sheet.getRow(r));
                if (h.containsKey("sku") && (h.containsKey("sales description") || h.containsKey("description"))) {
                    headerIdx = r; headers = h; break;
                }
            }
            if (headers == null) throw new IllegalArgumentException("No se encontro el header (SKU/Sales Description) en el Excel de Magnet");

            int cSku = col(headers, "sku");
            int cDesc = col(headers, "sales description", "description");
            int cPrice = col(headers, "sales price", "price");

            String currentBrand = null;
            for (int r = headerIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (rowEmpty(row)) continue;

                String desc = cellStr(row, cDesc);
                if (desc == null || desc.isBlank()) continue;
                String skuRaw = cellRawId(row, cSku);
                Double price = cellNum(row, cPrice);

                // fila-seccion = marca (sin SKU y sin precio)
                if ((skuRaw == null || skuRaw.isBlank()) && price == null) {
                    currentBrand = desc.trim();
                    continue;
                }

                ParsedRow pr = new ParsedRow();
                applyGtin(pr, skuRaw);
                pr.brand = currentBrand;
                pr.rawTitle = desc;
                pr.name = PerfumeNormalizer.cleanName(PerfumeNormalizer.stripBrandPrefix(currentBrand, desc));
                pr.ml = PerfumeNormalizer.mlFromMl(desc);
                pr.forma = PerfumeNormalizer.detectForma(desc);
                pr.costUsd = price;
                pr.inStock = true;
                pr.flashSale = false;
                pr.supplierSku = skuRaw;
                out.add(pr);
            }
        }
        return out;
    }
}
