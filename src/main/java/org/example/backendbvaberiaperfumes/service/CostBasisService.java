package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * FUENTE UNICA del costo USD de un producto. Antes habia dos bases divergentes:
 * el import repreciaba desde la oferta mas barata, pero recomputeAllPrices y la
 * ganancia del consolidado leian el Product.priceUsd congelado al crear el producto.
 *
 * Estrategias del costo base del precio publicado (app_config: pricing_basis):
 *  - CHEAPEST (default): oferta en stock mas barata. Precio mas competitivo; la
 *    erosion de margen la vigila el guard del optimizador de compras.
 *  - PRIORITY: oferta del proveedor prioritario (priorityToReachMin) si la hay;
 *    protege el margen cuando se fuerza su minimo, pero sobreprecia el resto.
 *  - WORST_PLAUSIBLE: la oferta mas cara dentro de una banda (plausible_band_pct)
 *    sobre la mas barata. Nunca erosiona, pero infla los precios publicados.
 */
@Service
public class CostBasisService {

    private final SupplierOfferRepository offerRepo;
    private final PricingService pricing;

    public CostBasisService(SupplierOfferRepository offerRepo, PricingService pricing) {
        this.offerRepo = offerRepo;
        this.pricing = pricing;
    }

    /** Ofertas utilizables para costear: en stock, proveedor activo, con costo. */
    public List<SupplierOffer> usableOffers(Long productId) {
        return offerRepo.findByProduct_IdAndInStockTrueAndSupplier_ActiveTrue(productId).stream()
                .filter(o -> o.getCostUsd() != null)
                .toList();
    }

    /** Costo base segun la estrategia configurada, calculado sobre ofertas ya cargadas. */
    public Double basisCostUsd(List<SupplierOffer> offers) {
        if (offers == null || offers.isEmpty()) return null;
        double cheapest = offers.stream().mapToDouble(SupplierOffer::getCostUsd).min().orElse(0);
        String basis = pricing.getPricingBasis();
        switch (basis) {
            case "PRIORITY": {
                return offers.stream()
                        .filter(o -> o.getSupplier() != null
                                && Boolean.TRUE.equals(o.getSupplier().getPriorityToReachMin()))
                        .mapToDouble(SupplierOffer::getCostUsd)
                        .min()
                        .orElse(cheapest);
            }
            case "WORST_PLAUSIBLE": {
                double band = 1 + pricing.getPlausibleBandPct() / 100.0;
                return offers.stream()
                        .mapToDouble(SupplierOffer::getCostUsd)
                        .filter(c -> c <= cheapest * band)
                        .max()
                        .orElse(cheapest);
            }
            case "CHEAPEST":
            default:
                return cheapest;
        }
    }

    /**
     * Costo actual de compra (independiente de la estrategia de precio): la oferta
     * utilizable mas barata; sin stock, la ultima conocida; sin ofertas, el legacy
     * Product.priceUsd (productos del seed sin proveedor conectado).
     */
    public Double currentCostUsd(Product p) {
        List<SupplierOffer> usable = usableOffers(p.getId());
        if (!usable.isEmpty()) {
            return usable.stream().mapToDouble(SupplierOffer::getCostUsd).min().orElse(0);
        }
        return offerRepo.findByProduct_Id(p.getId()).stream()
                .filter(o -> o.getCostUsd() != null)
                .max(Comparator.comparing(SupplierOffer::getLastImportedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(SupplierOffer::getCostUsd)
                .orElse(p.getPriceUsd());
    }

    /** Costo base del PRECIO publicado; sin ofertas usables cae al legacy (seed). */
    public Double basisCostUsdOrLegacy(Product p) {
        Double basis = basisCostUsd(usableOffers(p.getId()));
        return basis != null ? basis : currentCostUsd(p);
    }
}
