package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.springframework.stereotype.Service;

/**
 * Crea/resuelve el Product canonico a partir de una fila de proveedor.
 * El matching cross-proveedor se hace por GTIN confiable (en ExcelImportService);
 * aqui solo se construyen productos nuevos con datos por defecto (peso 600, sin imagen).
 */
@Service
public class ProductMatchingService {

    private final ProductRepository productRepo;

    public ProductMatchingService(ProductRepository productRepo) {
        this.productRepo = productRepo;
    }

    public Product createProduct(ParsedRow row, String trustedGtin, boolean conflict, String supplierName) {
        Product p = new Product();
        p.setSku(uniqueSku(supplierName, trustedGtin, row));
        p.setBrand(row.brand != null && !row.brand.isBlank() ? row.brand : "Generico");
        p.setName(row.name != null && !row.name.isBlank() ? row.name : row.rawTitle);
        p.setMl(row.ml);
        p.setForma(row.forma);
        p.setCategory(categoryFromTitle(row.rawTitle));
        p.setWeightG(600);                 // peso por defecto; se ajusta despues
        p.setImageUrl(row.imageUrl);       // foto (de Apify o manual) si la hay; si no, null
        p.setPriceUsd(row.costUsd);        // referencia; el costo real vive en SupplierOffer
        p.setAvailable(true);
        p.setArchived(false);
        p.setGtin(trustedGtin);            // null si no es confiable
        p.setGtinConflict(conflict);
        return productRepo.save(p);
    }

    public boolean sameProduct(ParsedRow a, ParsedRow b) {
        return PerfumeNormalizer.sameProduct(a.brand, a.name, a.ml, b.brand, b.name, b.ml);
    }

    private String categoryFromTitle(String title) {
        String t = title == null ? "" : PerfumeNormalizer.stripAccents(title).toLowerCase();
        if (t.contains("women") || t.contains("woman") || t.contains("femme")) return "women";
        if (t.contains("men") || t.contains("man") || t.contains("homme")) return "men";
        return "unisex";
    }

    private String uniqueSku(String supplierName, String trustedGtin, ParsedRow row) {
        String shortName = supplierName == null ? "SP" : supplierName.replaceAll("[^A-Za-z]", "").toUpperCase();
        if (shortName.length() > 2) shortName = shortName.substring(0, 2);
        String tail = trustedGtin != null ? trustedGtin
                : (row.supplierSku != null && !row.supplierSku.isBlank()
                    ? row.supplierSku.replaceAll("[^A-Za-z0-9]", "")
                    : PerfumeNormalizer.slug(row.brand, row.name, row.ml).toUpperCase());
        if (tail.length() > 40) tail = tail.substring(0, 40);
        String base = shortName + "-" + tail;
        String sku = base;
        int i = 1;
        while (productRepo.existsBySku(sku)) {
            sku = base + "-" + (i++);
        }
        return sku;
    }
}
