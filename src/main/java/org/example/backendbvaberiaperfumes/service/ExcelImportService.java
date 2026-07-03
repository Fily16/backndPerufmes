package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.parser.SupplierExcelParser;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ExcelImportService {

    private final Map<String, SupplierExcelParser> parsers = new HashMap<>();
    private final SupplierRepository supplierRepo;
    private final SupplierOfferRepository offerRepo;
    private final ProductRepository productRepo;
    private final ProductMatchingService matching;
    private final PricingService pricing;

    public ExcelImportService(List<SupplierExcelParser> parserList,
                              SupplierRepository supplierRepo,
                              SupplierOfferRepository offerRepo,
                              ProductRepository productRepo,
                              ProductMatchingService matching,
                              PricingService pricing) {
        for (SupplierExcelParser p : parserList) parsers.put(p.supplierName().toLowerCase(), p);
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.productRepo = productRepo;
        this.matching = matching;
        this.pricing = pricing;
    }

    @Transactional
    public ImportSummary importExcel(Long supplierId, InputStream is) throws Exception {
        Supplier supplier = supplierRepo.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado: " + supplierId));
        SupplierExcelParser parser = parsers.get(supplier.getName().toLowerCase());
        if (parser == null) {
            throw new IllegalArgumentException("No hay parser para el proveedor '" + supplier.getName() + "'");
        }

        ImportSummary summary = new ImportSummary();
        summary.setSupplierName(supplier.getName());

        List<ParsedRow> rows = parser.parse(is);
        summary.setRowsRead(rows.size());

        // 1. agrupar por GTIN (dentro del archivo) para detectar duplicados/colisiones
        Map<String, List<ParsedRow>> byGtin = new HashMap<>();
        for (ParsedRow r : rows) {
            if (r.hasGtin()) byGtin.computeIfAbsent(r.gtin, k -> new ArrayList<>()).add(r);
        }
        Set<String> collisionGtins = new HashSet<>();
        for (Map.Entry<String, List<ParsedRow>> e : byGtin.entrySet()) {
            if (e.getValue().size() > 1 && !allSameProduct(e.getValue())) collisionGtins.add(e.getKey());
        }

        // 2. procesar fila por fila
        Set<String> seenOfferKeys = new HashSet<>();
        Set<Long> touchedProducts = new HashSet<>();
        int created = 0, offersCreated = 0, offersUpdated = 0, trueDups = 0, noUpc = 0;

        for (ParsedRow row : rows) {
            boolean trusted;
            boolean conflict;
            String offerKey;

            if (!row.hasGtin()) {
                offerKey = "NOUPC#" + PerfumeNormalizer.slug(row.brand, row.name, row.ml);
                trusted = false; conflict = false; noUpc++;
            } else if (collisionGtins.contains(row.gtin)) {
                offerKey = row.gtin + "#" + PerfumeNormalizer.slug(row.brand, row.name, row.ml);
                trusted = false; conflict = true;
            } else {
                offerKey = row.gtin;
                trusted = true; conflict = false;
            }

            // duplicado real dentro del archivo (misma offerKey) -> se colapsa
            if (seenOfferKeys.contains(offerKey)) { trueDups++; continue; }
            seenOfferKeys.add(offerKey);

            SupplierOffer offer = offerRepo.findBySupplier_IdAndOfferKey(supplierId, offerKey).orElse(null);
            Product product;

            if (offer != null) {
                product = offer.getProduct();
                offersUpdated++;
            } else {
                if (trusted) {
                    Optional<Product> existing = productRepo.findByGtin(row.gtin);
                    if (existing.isPresent()) {
                        product = existing.get();
                    } else {
                        product = matching.createProduct(row, row.gtin, false, supplier.getName());
                        created++;
                    }
                } else {
                    product = matching.createProduct(row, null, conflict, supplier.getName());
                    created++;
                }
                offer = new SupplierOffer();
                offer.setProduct(product);
                offer.setSupplier(supplier);
                offer.setOfferKey(offerKey);
                offersCreated++;
            }

            offer.setGtin(row.gtin);
            offer.setSupplierSku(row.supplierSku);
            offer.setCostUsd(row.costUsd);
            offer.setInStock(row.inStock);
            offer.setFlashSale(row.flashSale);
            offer.setRawTitle(row.rawTitle);
            offer.setLastImportedAt(LocalDateTime.now());
            offerRepo.save(offer);
            touchedProducts.add(product.getId());
        }

        // 3. snapshot: ofertas de este proveedor que NO vinieron en este Excel -> fuera de stock
        int outOfStock = 0;
        for (SupplierOffer existing : offerRepo.findBySupplier_Id(supplierId)) {
            if (!seenOfferKeys.contains(existing.getOfferKey()) && Boolean.TRUE.equals(existing.getInStock())) {
                existing.setInStock(false);
                offerRepo.save(existing);
                outOfStock++;
            }
        }

        // 4. precio al publico (PEN) para los productos nuevos: oferta mas barata + courier + reempaque + S/15.
        //    Solo si no tiene precio (no pisa precios ajustados a mano). El cliente ve wholesalePricePen.
        int priced = 0;
        for (Long pid : touchedProducts) {
            Product p = productRepo.findById(pid).orElse(null);
            if (p == null) continue;
            if (p.getWholesalePricePen() != null && p.getWholesalePricePen() > 0) continue;
            List<SupplierOffer> inStock = offerRepo.findByProduct_IdAndInStockTrue(pid).stream()
                    .filter(o -> o.getCostUsd() != null)
                    .toList();
            if (inStock.isEmpty()) continue;
            double cheapest = inStock.stream().mapToDouble(SupplierOffer::getCostUsd).min().orElse(0);
            int weightG = p.getWeightG() != null ? p.getWeightG() : 600;
            double price = pricing.suggestedPublicPricePen(cheapest, weightG);
            p.setWholesalePricePen(price);
            if (p.getRetailPricePen() == null || p.getRetailPricePen() <= 0) p.setRetailPricePen(price);
            productRepo.save(p);
            priced++;
        }

        summary.setProductsCreated(created);
        summary.setOffersCreated(offersCreated);
        summary.setOffersUpdated(offersUpdated);
        summary.setTrueDuplicates(trueDups);
        summary.setCollisions(collisionGtins.size());
        summary.setNoUpcRows(noUpc);
        summary.setMarkedOutOfStock(outOfStock);
        summary.addNote("Productos canonicos no archivados: " + productRepo.countByArchivedFalse());
        summary.addNote("Ofertas en stock (este proveedor): "
                + offerRepo.findBySupplier_Id(supplierId).stream().filter(o -> Boolean.TRUE.equals(o.getInStock())).count());
        if (!collisionGtins.isEmpty()) {
            summary.addNote("Colisiones de UPC detectadas y separadas (NO fusionadas): " + collisionGtins.size());
        }
        return summary;
    }

    private boolean allSameProduct(List<ParsedRow> group) {
        ParsedRow first = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            if (!matching.sameProduct(first, group.get(i))) return false;
        }
        return true;
    }
}
