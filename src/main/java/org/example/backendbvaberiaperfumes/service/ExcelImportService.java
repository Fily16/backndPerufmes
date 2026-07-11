package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.ColumnMapping;
import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.parser.GenericSupplierParser;
import org.example.backendbvaberiaperfumes.service.parser.SupplierExcelParser;
import org.example.backendbvaberiaperfumes.util.PerfumeNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ExcelImportService {

    private final Map<String, SupplierExcelParser> parsers = new HashMap<>();
    private final GenericSupplierParser genericParser;
    private final SupplierRepository supplierRepo;
    private final SupplierOfferRepository offerRepo;
    private final ProductRepository productRepo;
    private final ProductMatchingService matching;
    private final PricingService pricing;

    public ExcelImportService(List<SupplierExcelParser> parserList,
                              GenericSupplierParser genericParser,
                              SupplierRepository supplierRepo,
                              SupplierOfferRepository offerRepo,
                              ProductRepository productRepo,
                              ProductMatchingService matching,
                              PricingService pricing) {
        // Se registran los parsers afinados por nombre; el generico NO (es fallback).
        for (SupplierExcelParser p : parserList) {
            if (!GenericSupplierParser.SENTINEL.equals(p.supplierName())) {
                parsers.put(p.supplierName().toLowerCase(), p);
            }
        }
        this.genericParser = genericParser;
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.productRepo = productRepo;
        this.matching = matching;
        this.pricing = pricing;
    }

    // =====================================================================
    // Parseo (afinado o generico). No escribe nada.
    // =====================================================================

    public static class ParsedData {
        public List<ParsedRow> rows = new ArrayList<>();
        public ColumnMapping mapping;         // null si se uso un parser afinado
        public List<String> headers = new ArrayList<>();
        public boolean generic;
    }

    public ParsedData parse(Supplier supplier, byte[] bytes, ColumnMapping override) throws Exception {
        ParsedData pd = new ParsedData();
        SupplierExcelParser tuned = parsers.get(supplier.getName().toLowerCase());
        if (tuned != null) {
            pd.rows = tuned.parse(new ByteArrayInputStream(bytes));
            pd.generic = false;
        } else {
            GenericSupplierParser.Detected d = genericParser.detect(bytes, override);
            pd.rows = d.rows;
            pd.mapping = d.mapping;
            pd.headers = d.headers;
            pd.generic = true;
        }
        return pd;
    }

    // =====================================================================
    // Vista previa (solo lectura, no toca catalogo).
    // =====================================================================

    @Transactional(readOnly = true)
    public ImportPreview buildPreview(Supplier supplier, ParsedData pd) {
        ImportPreview p = new ImportPreview();
        p.supplierName = supplier.getName();
        p.generic = pd.generic;
        p.mapping = pd.mapping;
        p.headers = pd.headers;
        p.rowsRead = pd.rows.size();

        Set<String> collisions = collisionGtins(pd.rows);
        p.collisions = collisions.size();

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < pd.rows.size(); i++) {
            ParsedRow row = pd.rows.get(i);
            OfferKey ok = offerKey(row, collisions);
            if (seen.contains(ok.key)) continue; // duplicado real dentro del archivo
            seen.add(ok.key);

            if (!row.hasGtin()) p.noUpcRows++;
            if (!row.inStock) p.outOfStock++;

            ImportPreview.Line line = new ImportPreview.Line();
            line.idx = i;
            line.brand = row.brand;
            line.name = row.name;
            line.rawTitle = row.rawTitle;
            line.ml = row.ml;
            line.upc = row.gtin;
            line.costUsd = row.costUsd;
            line.inStock = row.inStock;

            // Resolver producto igual que en commit (por offerKey del proveedor, luego por GTIN).
            SupplierOffer existing = offerRepo.findBySupplier_IdAndOfferKey(supplier.getId(), ok.key).orElse(null);
            Product match = null;
            if (existing != null) {
                match = existing.getProduct();
                p.updatedOffers++;
            } else if (ok.trusted) {
                match = productRepo.findByGtin(row.gtin).orElse(null);
                if (match == null) p.newProducts++;
            } else {
                p.newProducts++;
            }

            if (match != null) {
                // UPC ya existe: se conservan marca/nombre/ml/aromas/foto del producto. Solo se muestran.
                line.isNew = false;
                line.editable = false;
                line.matchedProductId = match.getId();
                line.matchedBrand = match.getBrand();
                line.matchedName = match.getName();
                line.matchedMl = match.getMl();
                line.matchedImageUrl = match.getImageUrl();
                line.currentPricePen = match.getWholesalePricePen();
            } else {
                // Producto nuevo (o sin UPC): el admin puede corregir marca/nombre/ml en la vista previa.
                line.isNew = true;
                line.editable = true;
            }

            if (row.costUsd != null) {
                line.newPricePen = simulateNewPrice(match, supplier, row.costUsd);
                if (line.currentPricePen != null && line.newPricePen != null) {
                    if (line.newPricePen < line.currentPricePen) p.priceDrops++;
                    else if (line.newPricePen > line.currentPricePen) p.priceRises++;
                }
            }
            p.rows.add(line);
        }
        return p;
    }

    /** Precio publico que quedaria si se publica esta fila (respeta priceLocked y otras ofertas activas). */
    private Double simulateNewPrice(Product match, Supplier supplier, double thisCost) {
        if (match == null) return pricing.suggestedPublicPricePen(thisCost, 600);
        if (Boolean.TRUE.equals(match.getPriceLocked())) return match.getWholesalePricePen();
        int weight = match.getWeightG() != null ? match.getWeightG() : 600;
        double cheapest = thisCost;
        for (SupplierOffer o : offerRepo.findByProduct_IdAndInStockTrueAndSupplier_ActiveTrue(match.getId())) {
            if (supplier.getId().equals(o.getSupplierId())) continue; // esta oferta se reemplaza por thisCost
            if (o.getCostUsd() != null) cheapest = Math.min(cheapest, o.getCostUsd());
        }
        return pricing.suggestedPublicPricePen(cheapest, weight);
    }

    // =====================================================================
    // Commit (escribe catalogo). Es lo unico que publica al cliente.
    // =====================================================================

    @Transactional
    public ImportSummary commit(Supplier supplier, List<ParsedRow> rows) {
        Long supplierId = supplier.getId();
        ImportSummary summary = new ImportSummary();
        summary.setSupplierName(supplier.getName());
        summary.setRowsRead(rows.size());

        Set<String> collisions = collisionGtins(rows);

        Set<String> seenOfferKeys = new HashSet<>();
        Set<Long> touchedProducts = new HashSet<>();
        int created = 0, offersCreated = 0, offersUpdated = 0, trueDups = 0, noUpc = 0;

        for (ParsedRow row : rows) {
            OfferKey ok = offerKey(row, collisions);
            if (!row.hasGtin()) noUpc++;

            if (seenOfferKeys.contains(ok.key)) { trueDups++; continue; }
            seenOfferKeys.add(ok.key);

            SupplierOffer offer = offerRepo.findBySupplier_IdAndOfferKey(supplierId, ok.key).orElse(null);
            Product product;
            if (offer != null) {
                product = offer.getProduct();
                offersUpdated++;
            } else {
                if (ok.trusted) {
                    Optional<Product> existing = productRepo.findByGtin(row.gtin);
                    if (existing.isPresent()) {
                        product = existing.get();
                    } else {
                        product = matching.createProduct(row, row.gtin, false, supplier.getName());
                        created++;
                    }
                } else {
                    product = matching.createProduct(row, null, ok.conflict, supplier.getName());
                    created++;
                }
                offer = new SupplierOffer();
                offer.setProduct(product);
                offer.setSupplier(supplier);
                offer.setOfferKey(ok.key);
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

        // Snapshot: ofertas de este proveedor que ya no vinieron en el Excel -> fuera de stock.
        int outOfStock = 0;
        for (SupplierOffer existing : offerRepo.findBySupplier_Id(supplierId)) {
            if (!seenOfferKeys.contains(existing.getOfferKey()) && Boolean.TRUE.equals(existing.getInStock())) {
                existing.setInStock(false);
                offerRepo.save(existing);
                outOfStock++;
                touchedProducts.add(existing.getProduct().getId());
            }
        }

        // Recalcular precios (el mas barato manda; oculta huerfanos). Respeta priceLocked.
        int priced = 0;
        for (Long pid : touchedProducts) {
            Product p = productRepo.findById(pid).orElse(null);
            if (p != null) { recomputeProductPrice(p); priced++; }
        }

        summary.setProductsCreated(created);
        summary.setOffersCreated(offersCreated);
        summary.setOffersUpdated(offersUpdated);
        summary.setTrueDuplicates(trueDups);
        summary.setCollisions(collisions.size());
        summary.setNoUpcRows(noUpc);
        summary.setMarkedOutOfStock(outOfStock);
        summary.addNote("Productos canonicos no archivados: " + productRepo.countByArchivedFalse());
        summary.addNote("Ofertas en stock (este proveedor): "
                + offerRepo.findBySupplier_Id(supplierId).stream().filter(o -> Boolean.TRUE.equals(o.getInStock())).count());
        if (!collisions.isEmpty()) {
            summary.addNote("Colisiones de UPC detectadas y separadas (NO fusionadas): " + collisions.size());
        }
        return summary;
    }

    /**
     * Recalcula el precio de un producto desde la oferta ACTIVA en stock mas barata.
     * Sin ofertas activas -> se oculta (available=false). Respeta priceLocked (edicion manual).
     */
    public void recomputeProductPrice(Product p) {
        List<SupplierOffer> offers = offerRepo.findByProduct_IdAndInStockTrueAndSupplier_ActiveTrue(p.getId()).stream()
                .filter(o -> o.getCostUsd() != null)
                .toList();
        if (offers.isEmpty()) {
            p.setAvailable(false);
            productRepo.save(p);
            return;
        }
        double cheapest = offers.stream().mapToDouble(SupplierOffer::getCostUsd).min().orElse(0);
        int weightG = p.getWeightG() != null ? p.getWeightG() : 600;
        p.setAvailable(true);
        if (!Boolean.TRUE.equals(p.getPriceLocked())) {
            double publicPen = pricing.suggestedPublicPricePen(cheapest, weightG);
            p.setWholesalePricePen(publicPen);
            if (p.getRetailPricePen() == null || p.getRetailPricePen() <= 0) p.setRetailPricePen(publicPen);
            if (p.getStockPricePen() != null) p.setStockPricePen(pricing.suggestedStockPricePen(cheapest, weightG));
        }
        productRepo.save(p);
    }

    // =====================================================================
    // Compatibilidad: import directo (parse + commit) usado por el endpoint viejo.
    // =====================================================================

    @Transactional
    public ImportSummary importExcel(Long supplierId, InputStream is) throws Exception {
        Supplier supplier = supplierRepo.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado: " + supplierId));
        ParsedData pd = parse(supplier, is.readAllBytes(), null);
        return commit(supplier, pd.rows);
    }

    // =====================================================================
    // Helpers de offerKey / colisiones (compartidos por preview y commit).
    // =====================================================================

    private static class OfferKey {
        String key;
        boolean trusted;
        boolean conflict;
        OfferKey(String key, boolean trusted, boolean conflict) {
            this.key = key; this.trusted = trusted; this.conflict = conflict;
        }
    }

    private OfferKey offerKey(ParsedRow row, Set<String> collisions) {
        if (!row.hasGtin()) {
            return new OfferKey("NOUPC#" + PerfumeNormalizer.slug(row.brand, row.name, row.ml), false, false);
        }
        if (collisions.contains(row.gtin)) {
            return new OfferKey(row.gtin + "#" + PerfumeNormalizer.slug(row.brand, row.name, row.ml), false, true);
        }
        return new OfferKey(row.gtin, true, false);
    }

    private Set<String> collisionGtins(List<ParsedRow> rows) {
        Map<String, List<ParsedRow>> byGtin = new HashMap<>();
        for (ParsedRow r : rows) {
            if (r.hasGtin()) byGtin.computeIfAbsent(r.gtin, k -> new ArrayList<>()).add(r);
        }
        Set<String> collisions = new HashSet<>();
        for (Map.Entry<String, List<ParsedRow>> e : byGtin.entrySet()) {
            if (e.getValue().size() > 1 && !allSameProduct(e.getValue())) collisions.add(e.getKey());
        }
        return collisions;
    }

    private boolean allSameProduct(List<ParsedRow> group) {
        ParsedRow first = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            if (!matching.sameProduct(first, group.get(i))) return false;
        }
        return true;
    }
}
