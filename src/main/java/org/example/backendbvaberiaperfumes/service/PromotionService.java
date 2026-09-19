package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.PromotionRequest;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Promotion;
import org.example.backendbvaberiaperfumes.model.PromotionItem;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.PromotionRepository;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PromotionService {

    private final PromotionRepository promotionRepo;
    private final ProductRepository productRepo;
    private final PricingService pricing;
    private final NsoGate nsoGate;

    public PromotionService(PromotionRepository promotionRepo, ProductRepository productRepo,
                            PricingService pricing, NsoGate nsoGate) {
        this.promotionRepo = promotionRepo;
        this.productRepo = productRepo;
        this.pricing = pricing;
        this.nsoGate = nsoGate;
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
        // Gate NSO activo: una promo con algun perfume del catalogo que ya no se vende no se muestra en la tienda.
        if (nsoGate.isActive()) {
            list = new ArrayList<>(list);
            list.removeIf(p -> !isPublicPromo(p));
        }
        return list;
    }

    /**
     * La tienda puede mostrar/vender la promo: todos sus perfumes del catalogo se pueden comprar (gate apagado:
     * siempre true). Los perfumes exclusivos de la promo (sin productId) no se revisan.
     */
    public boolean isPublicPromo(Promotion promo) {
        if (promo == null || !nsoGate.isActive()) return true;
        for (PromotionItem it : promo.getItems()) {
            if (it.getProductId() != null && !nsoGate.isPurchasable(it.getProductId())) return false;
        }
        return true;
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
        // Solo una promo que queda ACTIVA no puede llevar perfumes sin NSO: ocultarla (active=false) o editarla para
        // QUITAR el perfume bloqueado siempre se puede, aunque todavia lo tenga.
        if (Boolean.TRUE.equals(promo.getActive())) rejectBlockedItems(items);
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

    /**
     * Gate NSO activo: una promo ACTIVA no puede llevar perfumes del catalogo sin NSO (la tienda la ocultaria y no se
     * podria vender). Mensaje para la duena con los nombres. Los perfumes exclusivos (sin productId) no se validan.
     */
    private void rejectBlockedItems(List<PromotionItem> items) {
        if (!nsoGate.isActive()) return;
        Map<Long, String> blocked = new LinkedHashMap<>();
        for (PromotionItem it : items) {
            Long pid = it.getProductId();
            if (pid != null && !nsoGate.isPurchasable(pid)) blocked.putIfAbsent(pid, "«" + it.getName() + "»");
        }
        if (blocked.isEmpty()) return;
        List<String> names = new ArrayList<>(blocked.values());
        throw new IllegalArgumentException(names.size() == 1
                ? names.get(0) + " no tiene NSO: no se puede mostrar en una promoción activa. Quítalo u oculta la promoción para guardar."
                : String.join(", ", names) + " no tienen NSO: no se pueden mostrar en una promoción activa. Quítalos u oculta la promoción para guardar.");
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
