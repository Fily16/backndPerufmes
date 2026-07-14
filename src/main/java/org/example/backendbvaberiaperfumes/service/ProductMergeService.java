package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.MatchCandidate;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.PromotionItem;
import org.example.backendbvaberiaperfumes.model.OrderItem;
import org.example.backendbvaberiaperfumes.model.RetailInventory;
import org.example.backendbvaberiaperfumes.model.RetailSale;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fusiona un producto duplicado dentro de su canonico, re-apuntando TODAS las
 * referencias (ofertas de proveedor, items de pedidos, inventario retail, ventas,
 * promociones) y conservando la curaduria (foto, notas, peso).
 *
 * El duplicado NO se borra: queda archivado con mergedIntoId (auditoria y
 * trazabilidad de pedidos historicos). Solo se ejecuta con aprobacion del admin.
 */
@Service
public class ProductMergeService {

    private final ProductRepository productRepo;
    private final SupplierOfferRepository offerRepo;
    private final OrderItemRepository orderItemRepo;
    private final RetailInventoryRepository retailInvRepo;
    private final RetailSaleRepository retailSaleRepo;
    private final PromotionItemRepository promoItemRepo;
    private final MatchCandidateRepository candidateRepo;
    private final ExcelImportService importService;

    public ProductMergeService(ProductRepository productRepo,
                               SupplierOfferRepository offerRepo,
                               OrderItemRepository orderItemRepo,
                               RetailInventoryRepository retailInvRepo,
                               RetailSaleRepository retailSaleRepo,
                               PromotionItemRepository promoItemRepo,
                               MatchCandidateRepository candidateRepo,
                               ExcelImportService importService) {
        this.productRepo = productRepo;
        this.offerRepo = offerRepo;
        this.orderItemRepo = orderItemRepo;
        this.retailInvRepo = retailInvRepo;
        this.retailSaleRepo = retailSaleRepo;
        this.promoItemRepo = promoItemRepo;
        this.candidateRepo = candidateRepo;
        this.importService = importService;
    }

    public static class MergeResult {
        public Long canonicalId;
        public Long duplicateId;
        public int offersMoved;
        public int offersDropped;      // mismo proveedor+offerKey en ambos: se conserva la mas reciente
        public int orderItemsMoved;
        public int retailLotsMoved;
        public int retailSalesMoved;
        public int promoItemsMoved;
        public boolean gtinAdopted;    // el canonico tomo el GTIN del duplicado
    }

    @Transactional
    public MergeResult merge(Long canonicalId, Long duplicateId) {
        if (canonicalId == null || duplicateId == null || canonicalId.equals(duplicateId)) {
            throw new IllegalArgumentException("Ids de fusion invalidos");
        }
        Product canonical = productRepo.findById(canonicalId)
                .orElseThrow(() -> new IllegalArgumentException("Producto canonico no existe: " + canonicalId));
        Product duplicate = productRepo.findById(duplicateId)
                .orElseThrow(() -> new IllegalArgumentException("Producto duplicado no existe: " + duplicateId));
        if (canonical.getMergedIntoId() != null) {
            throw new IllegalStateException("El canonico ya fue fusionado en otro producto (id "
                    + canonical.getMergedIntoId() + ")");
        }
        if (duplicate.getMergedIntoId() != null) {
            throw new IllegalStateException("El duplicado ya fue fusionado antes");
        }

        MergeResult res = new MergeResult();
        res.canonicalId = canonicalId;
        res.duplicateId = duplicateId;

        // 1. Ofertas de proveedor. Cuidado con el unique (supplier_id, offer_key):
        //    si el mismo proveedor tiene la MISMA offerKey en ambos productos, se
        //    conserva la importada mas recientemente y la otra se elimina.
        Map<String, SupplierOffer> canonicalOffers = new HashMap<>();
        for (SupplierOffer o : offerRepo.findByProduct_Id(canonicalId)) {
            canonicalOffers.put(o.getSupplierId() + "#" + o.getOfferKey(), o);
        }
        for (SupplierOffer o : offerRepo.findByProduct_Id(duplicateId)) {
            SupplierOffer clash = canonicalOffers.get(o.getSupplierId() + "#" + o.getOfferKey());
            if (clash != null) {
                LocalDateTime a = o.getLastImportedAt(), b = clash.getLastImportedAt();
                boolean keepDuplicateOne = a != null && (b == null || a.isAfter(b));
                if (keepDuplicateOne) {
                    offerRepo.delete(clash);
                    offerRepo.flush(); // libera el unique antes de re-apuntar
                    o.setProduct(canonical);
                    offerRepo.save(o);
                    canonicalOffers.put(o.getSupplierId() + "#" + o.getOfferKey(), o);
                    res.offersMoved++;
                } else {
                    offerRepo.delete(o);
                }
                res.offersDropped++;
            } else {
                o.setProduct(canonical);
                offerRepo.save(o);
                canonicalOffers.put(o.getSupplierId() + "#" + o.getOfferKey(), o);
                res.offersMoved++;
            }
        }

        // 2. Items de pedidos historicos (los snapshots de precio quedan intactos).
        for (OrderItem it : orderItemRepo.findByProductId(duplicateId)) {
            it.setProduct(canonical);
            orderItemRepo.save(it);
            res.orderItemsMoved++;
        }

        // 3. Inventario retail: los lotes se re-apuntan tal cual (el stock es por lote).
        for (RetailInventory lot : retailInvRepo.findByProductId(duplicateId)) {
            lot.setProduct(canonical);
            retailInvRepo.save(lot);
            res.retailLotsMoved++;
        }
        for (RetailSale sale : retailSaleRepo.findByProductId(duplicateId)) {
            sale.setProduct(canonical);
            retailSaleRepo.save(sale);
            res.retailSalesMoved++;
        }

        // 4. Promociones (columna plana productId).
        for (PromotionItem pi : promoItemRepo.findByProductId(duplicateId)) {
            pi.setProductId(canonicalId);
            promoItemRepo.save(pi);
            res.promoItemsMoved++;
        }

        // 5. Enriquecer el canonico con la curaduria del duplicado (solo lo que falta).
        if ((canonical.getGtin() == null || canonical.getGtin().isBlank())
                && duplicate.getGtin() != null && !duplicate.getGtin().isBlank()) {
            String g = duplicate.getGtin();
            duplicate.setGtin(null);           // libera la unicidad logica de findByGtin
            productRepo.save(duplicate);
            productRepo.flush();
            canonical.setGtin(g);
            res.gtinAdopted = true;
        }
        if (isBlank(canonical.getImageUrl()) && !isBlank(duplicate.getImageUrl())) {
            canonical.setImageUrl(duplicate.getImageUrl());
        }
        if (isBlank(canonical.getNotesTop()) && !isBlank(duplicate.getNotesTop())) {
            canonical.setNotesTop(duplicate.getNotesTop());
            canonical.setNotesMiddle(duplicate.getNotesMiddle());
            canonical.setNotesBase(duplicate.getNotesBase());
            canonical.setOlfactiveFamily(duplicate.getOlfactiveFamily());
            canonical.setOccasion(duplicate.getOccasion());
            canonical.setSeasons(duplicate.getSeasons());
        }
        if (isBlank(canonical.getDescription()) && !isBlank(duplicate.getDescription())) {
            canonical.setDescription(duplicate.getDescription());
        }
        // Peso: 600 es el valor por defecto de los imports; un peso distinto fue ajustado a mano.
        if ((canonical.getWeightG() == null || canonical.getWeightG() == 600)
                && duplicate.getWeightG() != null && duplicate.getWeightG() != 600) {
            canonical.setWeightG(duplicate.getWeightG());
        }
        if (canonical.getMl() == null && duplicate.getMl() != null) {
            canonical.setMl(duplicate.getMl());
        }
        canonical.setMatchPending(false);
        productRepo.save(canonical);

        // 6. El duplicado queda archivado apuntando al canonico (sin hard delete).
        duplicate.setArchived(true);
        duplicate.setAvailable(false);
        duplicate.setMatchPending(false);
        duplicate.setMergedIntoId(canonicalId);
        productRepo.save(duplicate);

        // 7. Candidatos pendientes que involucran al duplicado quedan resueltos.
        for (MatchCandidate mc : candidateRepo.findBySourceProductIdAndStatus(duplicateId, "PENDING")) {
            mc.setStatus(canonicalId.equals(mc.getTargetProductId()) ? "ACCEPTED" : "REJECTED");
            mc.setResolvedAt(LocalDateTime.now());
            mc.setResolvedBy("merge");
            candidateRepo.save(mc);
        }
        for (MatchCandidate mc : candidateRepo.findByTargetProductIdAndStatus(duplicateId, "PENDING")) {
            mc.setStatus("REJECTED");
            mc.setResolvedAt(LocalDateTime.now());
            mc.setResolvedBy("merge");
            candidateRepo.save(mc);
        }

        // 8. Reprecia el canonico desde sus ofertas (ahora incluye las del duplicado).
        importService.recomputeProductPrice(canonical);
        return res;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
