package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.dto.ColumnMapping;
import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.MatchCandidate;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.MatchCandidateRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.matching.FingerprintExtractor;
import org.example.backendbvaberiaperfumes.service.matching.MatchingEngine;
import org.example.backendbvaberiaperfumes.service.matching.ProductFingerprint;
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
    private final MatchingEngine engine;
    private final MatchCandidateRepository candidateRepo;
    private final CostBasisService costBasis;
    private final ObjectMapper json = new ObjectMapper();

    public ExcelImportService(List<SupplierExcelParser> parserList,
                              GenericSupplierParser genericParser,
                              SupplierRepository supplierRepo,
                              SupplierOfferRepository offerRepo,
                              ProductRepository productRepo,
                              ProductMatchingService matching,
                              PricingService pricing,
                              MatchingEngine engine,
                              MatchCandidateRepository candidateRepo,
                              CostBasisService costBasis) {
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
        this.engine = engine;
        this.candidateRepo = candidateRepo;
        this.costBasis = costBasis;
    }

    // =====================================================================
    // Parseo (afinado o generico). No escribe nada.
    // =====================================================================

    public static class ParsedData {
        public List<ParsedRow> rows = new ArrayList<>();
        public ColumnMapping mapping;         // null si se uso un parser afinado
        public List<String> headers = new ArrayList<>();
        public boolean generic;
        public boolean layoutFallback;        // el parser afinado no reconocio el layout; se uso el generico
    }

    public ParsedData parse(Supplier supplier, byte[] bytes, ColumnMapping override) throws Exception {
        ParsedData pd = new ParsedData();
        SupplierExcelParser tuned = parsers.get(supplier.getName().toLowerCase());
        if (tuned != null && override == null) {
            try {
                pd.rows = tuned.parse(new ByteArrayInputStream(bytes));
                pd.generic = false;
                return pd;
            } catch (Exception layoutChanged) {
                // El proveedor cambio el layout del Excel (caso real: Zimaxx viejo vs nuevo).
                // Caer al parser generico en vez de rechazar el archivo.
                pd.layoutFallback = true;
            }
        }
        // Sin override explicito, usar el perfil aprendido del proveedor (si existe).
        ColumnMapping effective = override;
        if (effective == null && supplier.getParserProfileJson() != null
                && !supplier.getParserProfileJson().isBlank()) {
            try {
                effective = json.readValue(supplier.getParserProfileJson(), ColumnMapping.class);
            } catch (Exception ignored) { /* perfil corrupto: autodetectar */ }
        }
        GenericSupplierParser.Detected d = genericParser.detect(bytes, effective);
        pd.rows = d.rows;
        pd.mapping = d.mapping;
        pd.headers = d.headers;
        pd.generic = true;
        return pd;
    }

    /** ¿El costo esta fuera del rango plausible? (typos del proveedor, ej. $2 por 200ml). */
    private boolean isSuspicious(ParsedRow row) {
        if (row.costUsd == null) return false;
        double min = pricing.getMinPlausibleCostUsd();
        double max = pricing.getMaxPlausibleCostUsd();
        return row.costUsd < min || row.costUsd > max;
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
        p.layoutFallback = pd.layoutFallback;

        Set<String> collisions = collisionGtins(pd.rows);
        p.collisions = collisions.size();

        MatchingEngine.Session session = engine.openSession();

        // PRECARGA en memoria: contra una BD remota (Aiven), consultar por fila
        // convertia un Excel de 1000 filas en ~3000 viajes de red (minutos).
        Map<String, SupplierOffer> offersByKey = new HashMap<>();
        for (SupplierOffer o : offerRepo.findBySupplier_Id(supplier.getId())) {
            offersByKey.put(o.getOfferKey(), o);
        }
        Map<Long, List<SupplierOffer>> inStockByProduct = new HashMap<>();
        for (SupplierOffer o : offerRepo.findAllInStockActive()) {
            inStockByProduct.computeIfAbsent(o.getProduct().getId(), k -> new ArrayList<>()).add(o);
        }

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
            line.gtinStatus = row.gtinStatus;
            line.suspicious = isSuspicious(row);
            if (line.suspicious) p.suspiciousRows++;

            // Resolver producto igual que en commit (offerKey del proveedor -> GTIN -> matching L2).
            SupplierOffer existing = offersByKey.get(ok.key);
            Product match = null;
            if (existing != null) {
                match = existing.getProduct();
                line.matchLevel = "EXISTING";
                p.updatedOffers++;
            } else if (ok.trusted) {
                match = session.byGtin(row.gtin);
                if (match == null) {
                    line.matchLevel = "NEW";
                    p.newProducts++;
                } else {
                    line.matchLevel = "L1";
                }
            } else if (ok.conflict) {
                line.matchLevel = "NEW";
                p.newProducts++;
            } else {
                MatchingEngine.MatchOutcome outcome = session.resolve(row);
                if (outcome.autoMatch != null) {
                    match = outcome.autoMatch;
                    line.matchLevel = "L2_AUTO";
                    line.matchScore = 1.0;
                    p.l2AutoMatched++;
                } else if (!outcome.reviewCandidates.isEmpty()) {
                    line.matchLevel = "L2_REVIEW";
                    line.matchScore = outcome.reviewCandidates.get(0).score;
                    p.reviewCandidates++;
                    p.newProducts++;
                } else {
                    line.matchLevel = "NEW";
                    p.newProducts++;
                }
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
                line.newPricePen = simulateNewPrice(match, supplier, row.costUsd, inStockByProduct);
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
    private Double simulateNewPrice(Product match, Supplier supplier, double thisCost,
                                    Map<Long, List<SupplierOffer>> inStockByProduct) {
        if (match == null) return pricing.suggestedPublicPricePen(thisCost, 600);
        if (Boolean.TRUE.equals(match.getPriceLocked())) return match.getWholesalePricePen();
        int weight = match.getWeightG() != null ? match.getWeightG() : 600;
        double cheapest = thisCost;
        for (SupplierOffer o : inStockByProduct.getOrDefault(match.getId(), List.of())) {
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
        return commit(supplier, rows, Set.of());
    }

    @Transactional
    public ImportSummary commit(Supplier supplier, List<ParsedRow> rows, Set<Integer> approvedSuspiciousIdx) {
        Long supplierId = supplier.getId();
        ImportSummary summary = new ImportSummary();
        summary.setSupplierName(supplier.getName());
        summary.setRowsRead(rows.size());

        Set<String> collisions = collisionGtins(rows);

        // Sesion de matching L2: catalogo indexado en memoria; los productos creados
        // en este mismo commit se agregan para que filas posteriores los encuentren.
        MatchingEngine.Session session = engine.openSession();

        // PRECARGA de las ofertas existentes del proveedor (1 consulta en vez de 1 por fila).
        Map<String, SupplierOffer> offersByKey = new HashMap<>();
        for (SupplierOffer o : offerRepo.findBySupplier_Id(supplierId)) {
            offersByKey.put(o.getOfferKey(), o);
        }

        Set<String> seenOfferKeys = new HashSet<>();
        Set<Long> touchedProducts = new HashSet<>();
        Set<String> seenCandidatePairs = new HashSet<>();
        int created = 0, offersCreated = 0, offersUpdated = 0, trueDups = 0, noUpc = 0;
        int l2Auto = 0, reviewQueued = 0, suspicious = 0;

        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            ParsedRow row = rows.get(rowIdx);
            OfferKey ok = offerKey(row, collisions);

            if (seenOfferKeys.contains(ok.key)) { trueDups++; continue; }
            seenOfferKeys.add(ok.key);

            if (!row.hasGtin()) noUpc++;

            // Costo fuera del rango plausible (typo probable, ej. $2 por 200ml): la oferta
            // se guarda pero FUERA de stock, para que no toque precios ni la asignacion,
            // salvo que el admin la haya aprobado explicitamente en el preview.
            if (isSuspicious(row) && !approvedSuspiciousIdx.contains(rowIdx)) {
                row.inStock = false;
                suspicious++;
            }

            SupplierOffer offer = offersByKey.get(ok.key);
            Product product;
            MatchingEngine.MatchOutcome outcome = null; // solo filas nuevas sin GTIN confiable
            boolean matchedByGtin = false;
            if (offer != null) {
                product = offer.getProduct();
                offersUpdated++;
            } else {
                if (ok.trusted) {
                    Product existing = session.byGtin(row.gtin);
                    if (existing != null) {
                        product = existing;
                        matchedByGtin = true;
                    } else {
                        product = matching.createProduct(row, row.gtin, false, supplier.getName());
                        created++;
                        session.addProduct(product);
                    }
                } else if (ok.conflict) {
                    product = matching.createProduct(row, null, true, supplier.getName());
                    created++;
                    session.addProduct(product);
                } else {
                    // Sin UPC confiable: matching por nombre (L2) contra el catalogo.
                    outcome = session.resolve(row);
                    if (outcome.autoMatch != null) {
                        product = outcome.autoMatch;
                        l2Auto++;
                    } else {
                        product = matching.createProduct(row, null, false, supplier.getName());
                        created++;
                        if (!outcome.reviewCandidates.isEmpty()) {
                            product.setMatchPending(true);
                            productRepo.save(product);
                        }
                        session.addProduct(product);
                    }
                }
                offer = new SupplierOffer();
                offer.setProduct(product);
                offer.setSupplier(supplier);
                offer.setOfferKey(ok.key);
                offersCreated++;
            }

            offer.setGtin(row.gtin);
            offer.setGtinRaw(row.gtinRaw);
            offer.setGtinStatus(row.gtinStatus);
            offer.setSupplierSku(row.supplierSku);
            offer.setCostUsd(row.costUsd);
            offer.setInStock(row.inStock);
            offer.setFlashSale(row.flashSale);
            offer.setRawTitle(row.rawTitle);
            offer.setLastImportedAt(LocalDateTime.now());
            offerRepo.save(offer);
            touchedProducts.add(product.getId());

            // Cola de revision: candidatos probables (posible duplicado / typo de GTIN).
            if (outcome != null && outcome.autoMatch == null && !outcome.reviewCandidates.isEmpty()) {
                boolean any = false;
                for (MatchingEngine.ReviewCandidate rc : outcome.reviewCandidates) {
                    if (queueCandidate(rc.kind, product.getId(), rc.product.getId(),
                            offer.getId(), rc.score, rc.reasons, seenCandidatePairs)) {
                        any = true;
                    }
                }
                if (any) reviewQueued++;
            }

            // Mismo GTIN pero atributos contradictorios entre proveedores (ej. 200ml vs 100ml).
            if (matchedByGtin) {
                queueAttrConflictIfNeeded(row, product, offer);
            }
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
        // Las ofertas cotizables se cargan en UNA consulta (contra Aiven, 1 por producto = minutos).
        int priced = 0;
        Map<Long, List<SupplierOffer>> usableByProduct = new HashMap<>();
        for (SupplierOffer o : offerRepo.findAllInStockActive()) {
            usableByProduct.computeIfAbsent(o.getProduct().getId(), k -> new ArrayList<>()).add(o);
        }
        for (Long pid : touchedProducts) {
            Product p = productRepo.findById(pid).orElse(null);
            if (p != null) {
                recomputeProductPrice(p, usableByProduct.getOrDefault(pid, List.of()));
                priced++;
            }
        }

        summary.setProductsCreated(created);
        summary.setOffersCreated(offersCreated);
        summary.setOffersUpdated(offersUpdated);
        summary.setTrueDuplicates(trueDups);
        summary.setCollisions(collisions.size());
        summary.setNoUpcRows(noUpc);
        summary.setMarkedOutOfStock(outOfStock);
        summary.setL2AutoMatched(l2Auto);
        summary.setReviewQueued(reviewQueued);
        summary.setSuspiciousRows(suspicious);
        if (suspicious > 0) {
            summary.addNote("Filas con costo fuera de rango guardadas FUERA de stock (aprobarlas desde el preview): " + suspicious);
        }
        summary.addNote("Productos canonicos no archivados: " + productRepo.countByArchivedFalse());
        summary.addNote("Ofertas en stock (este proveedor): "
                + offerRepo.findBySupplier_Id(supplierId).stream().filter(o -> Boolean.TRUE.equals(o.getInStock())).count());
        if (!collisions.isEmpty()) {
            summary.addNote("Colisiones de UPC detectadas y separadas (NO fusionadas): " + collisions.size());
        }
        if (l2Auto > 0) {
            summary.addNote("Filas sin UPC enganchadas por nombre a productos existentes: " + l2Auto);
        }
        if (reviewQueued > 0) {
            summary.addNote("Posibles duplicados enviados a la cola de revision: " + reviewQueued);
        }
        return summary;
    }

    /** Crea un MatchCandidate si el par (source,target) no fue propuesto antes. */
    private boolean queueCandidate(String kind, Long sourceId, Long targetId, Long offerId,
                                   double score, List<String> reasons, Set<String> seenPairs) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) return false;
        String pairKey = sourceId + ">" + targetId;
        if (!seenPairs.add(pairKey)) return false;
        if (candidateRepo.existsBySourceProductIdAndTargetProductId(sourceId, targetId)) return false;
        MatchCandidate mc = new MatchCandidate();
        mc.setKind(kind);
        mc.setSourceProductId(sourceId);
        mc.setTargetProductId(targetId);
        mc.setSupplierOfferId(offerId);
        mc.setScore(score);
        mc.setReasonsJson(toJson(reasons));
        candidateRepo.save(mc);
        return true;
    }

    /** Mismo GTIN, atributos incompatibles (tamano). El GTIN manda, pero se avisa al admin. */
    private void queueAttrConflictIfNeeded(ParsedRow row, Product product, SupplierOffer offer) {
        if (row.ml == null || product.getMl() == null) return;
        int tol = (int) Math.max(3, Math.max(row.ml, product.getMl()) * 0.03);
        if (Math.abs(row.ml - product.getMl()) <= tol) return;
        if (candidateRepo.existsBySupplierOfferIdAndKind(offer.getId(), "ATTR_CONFLICT")) return;
        ProductFingerprint rowFp = FingerprintExtractor.fromRow(row);
        MatchCandidate mc = new MatchCandidate();
        mc.setKind("ATTR_CONFLICT");
        mc.setTargetProductId(product.getId());
        mc.setSupplierOfferId(offer.getId());
        mc.setScore(0.0);
        mc.setReasonsJson(toJson(List.of(
                "mismo GTIN " + row.gtin + " pero tamano contradictorio: proveedor dice "
                        + row.ml + "ml, catalogo tiene " + product.getMl() + "ml",
                "huella del proveedor: " + rowFp)));
        candidateRepo.save(mc);
    }

    private String toJson(List<String> reasons) {
        try {
            String s = json.writeValueAsString(reasons);
            return s.length() > 1990 ? s.substring(0, 1990) : s;
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Recalcula el precio de un producto desde sus ofertas activas en stock, con la
     * estrategia de costo base configurada (CostBasisService; default: la mas barata).
     * Sin ofertas activas -> se oculta (available=false). Respeta priceLocked (edicion manual).
     * Ademas RE-SINCRONIZA el legacy Product.priceUsd para que todos los lectores viejos
     * (recalculo por config, ganancia de consolidado, compra de tienda) dejen de derivar.
     */
    public void recomputeProductPrice(Product p) {
        recomputeProductPrice(p, costBasis.usableOffers(p.getId()));
    }

    /** Variante con las ofertas YA cargadas (para el import masivo: evita 1 consulta por producto). */
    public void recomputeProductPrice(Product p, List<SupplierOffer> offers) {
        if (offers.isEmpty()) {
            p.setAvailable(false);
            productRepo.save(p);
            return;
        }
        double basisCost = costBasis.basisCostUsd(offers);
        int weightG = p.getWeightG() != null ? p.getWeightG() : 600;
        p.setAvailable(true);
        p.setPriceUsd(basisCost); // re-sync del costo legacy: una sola base de costo
        if (!Boolean.TRUE.equals(p.getPriceLocked())) {
            double publicPen = pricing.suggestedPublicPricePen(basisCost, weightG);
            p.setWholesalePricePen(publicPen);
            if (p.getRetailPricePen() == null || p.getRetailPricePen() <= 0) p.setRetailPricePen(publicPen);
            if (p.getStockPricePen() != null) p.setStockPricePen(pricing.suggestedStockPricePen(basisCost, weightG));
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
