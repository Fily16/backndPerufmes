package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.PromotionRequest;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Promotion;
import org.example.backendbvaberiaperfumes.model.PromotionItem;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.PromotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class PromotionService {

    private final PromotionRepository promotionRepo;
    private final ProductRepository productRepo;
    private final PricingService pricing;

    public PromotionService(PromotionRepository promotionRepo, ProductRepository productRepo,
                            PricingService pricing) {
        this.promotionRepo = promotionRepo;
        this.productRepo = productRepo;
        this.pricing = pricing;
    }

    @Transactional(readOnly = true)
    public List<Promotion> findAll() {
        List<Promotion> all = promotionRepo.findAll();
        all.forEach(p -> p.getItems().size()); // inicializa lazy
        return all;
    }

    @Transactional(readOnly = true)
    public List<Promotion> activeForStore() {
        List<Promotion> list = promotionRepo.findActiveForStore(LocalDate.now());
        list.forEach(p -> p.getItems().size());
        return list;
    }

    @Transactional(readOnly = true)
    public Promotion getById(Long id) {
        Promotion p = promotionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Promoción no encontrada: " + id));
        p.getItems().size();
        return p;
    }

    @Transactional
    public Promotion create(PromotionRequest req) {
        Promotion promo = new Promotion();
        apply(promo, req);
        return promotionRepo.save(promo);
    }

    @Transactional
    public Promotion update(Long id, PromotionRequest req) {
        Promotion promo = promotionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Promoción no encontrada: " + id));
        promo.getItems().clear(); // orphanRemoval borra los ítems viejos
        apply(promo, req);
        return promotionRepo.save(promo);
    }

    @Transactional
    public void delete(Long id) {
        promotionRepo.deleteById(id);
    }

    /** Rellena la promo desde el request, resolviendo ítems y calculando la ganancia. */
    private void apply(Promotion promo, PromotionRequest req) {
        promo.setName(req.getName());
        promo.setImageUrl(req.getImageUrl());
        promo.setImageData(req.getImageData());
        promo.setPricePen(req.getPricePen() != null ? req.getPricePen() : 0.0);
        promo.setStockQty(req.getStockQty() != null ? Math.max(0, req.getStockQty()) : 0);
        promo.setActive(req.getActive() == null || req.getActive());
        if (req.getValidUntil() != null && !req.getValidUntil().isBlank()) {
            try { promo.setValidUntil(LocalDate.parse(req.getValidUntil())); }
            catch (Exception e) { promo.setValidUntil(null); }
        } else {
            promo.setValidUntil(null);
        }

        List<PromotionItem> items = new ArrayList<>();
        boolean allCatalog = true;
        double sumLandedPen = 0;

        List<PromotionRequest.ItemReq> reqItems = req.getItems() != null ? req.getItems() : new ArrayList<>();
        for (PromotionRequest.ItemReq ir : reqItems) {
            PromotionItem item = new PromotionItem();
            item.setPromotion(promo);
            if (ir.getProductId() != null) {
                Product p = productRepo.findById(ir.getProductId())
                        .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado: " + ir.getProductId()));
                item.setProductId(p.getId());
                item.setName(ir.getName() != null && !ir.getName().isBlank()
                        ? ir.getName() : (p.getBrand() + " " + p.getName()));
                item.setImageUrl(ir.getImageUrl() != null && !ir.getImageUrl().isBlank()
                        ? ir.getImageUrl() : p.getImageUrl());
                if (p.getPriceUsd() != null && p.getWeightG() != null) {
                    sumLandedPen += pricing.landedPen(p.getPriceUsd(), p.getWeightG());
                } else {
                    allCatalog = false; // sin costo conocido no se puede sugerir
                }
            } else {
                // Perfume exclusivo de la promo (no entra al catálogo global)
                if (ir.getName() == null || ir.getName().isBlank()) {
                    throw new IllegalArgumentException("Cada perfume exclusivo de la promo necesita un nombre.");
                }
                item.setProductId(null);
                item.setName(ir.getName());
                item.setImageUrl(ir.getImageUrl());
                allCatalog = false;
            }
            items.add(item);
        }

        if (items.isEmpty()) {
            throw new IllegalArgumentException("La promoción debe incluir al menos un perfume.");
        }
        promo.getItems().addAll(items);

        // Ganancia: manual si viene; si no, sugerida (solo si todos son del catálogo con costo).
        if (req.getProfitPen() != null) {
            promo.setProfitPen(req.getProfitPen());
        } else if (allCatalog) {
            promo.setProfitPen(Math.round((promo.getPricePen() - sumLandedPen) * 100.0) / 100.0);
        } else {
            throw new IllegalArgumentException(
                    "Debes ingresar la ganancia de la promoción (incluye un perfume exclusivo o sin costo).");
        }
    }

    /** Ganancia sugerida para el editor: precio − Σ puesto en Perú (solo ítems del catálogo). */
    @Transactional(readOnly = true)
    public Double suggestProfit(double pricePen, List<Long> productIds) {
        double sum = 0;
        for (Long id : productIds) {
            Product p = productRepo.findById(id).orElse(null);
            if (p == null || p.getPriceUsd() == null || p.getWeightG() == null) return null;
            sum += pricing.landedPen(p.getPriceUsd(), p.getWeightG());
        }
        return Math.round((pricePen - sum) * 100.0) / 100.0;
    }
}
