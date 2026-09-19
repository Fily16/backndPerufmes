package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.OrderItemRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Recomendaciones hibridas (no random, no estaticas):
 *  1) Co-compra REAL: productos que aparecen en los mismos pedidos (historial) -> domina el ranking.
 *  2) Similaridad por CONTENIDO: misma marca, misma familia (token de nombre), mismo genero, precio cercano.
 * Cuando aun no hay historial para un producto, cae a contenido + mas vendidos + misma marca.
 * Devuelve entidades Product para que el frontend reutilice la misma tarjeta del catalogo.
 */
@Service
public class RecommendationService {

    private final ProductRepository productRepo;
    private final OrderItemRepository orderItemRepo;
    private final SupplierOfferRepository offerRepo;
    private final NsoGate nsoGate;

    public RecommendationService(ProductRepository productRepo,
                                 OrderItemRepository orderItemRepo,
                                 SupplierOfferRepository offerRepo,
                                 NsoGate nsoGate) {
        this.productRepo = productRepo;
        this.orderItemRepo = orderItemRepo;
        this.offerRepo = offerRepo;
        this.nsoGate = nsoGate;
    }

    /** Similares + frecuentemente pedidos juntos para un producto. */
    public List<Product> relatedFor(Long productId, int limit) {
        Product base = productRepo.findById(productId).orElse(null);
        if (base == null) return List.of();
        return recommend(List.of(base), limit);
    }

    /** Cross-sell del carrito: combina las recomendaciones de todos los items. */
    public List<Product> crossSell(List<Long> ids, int limit) {
        List<Product> bases = ids.stream()
                .map(id -> productRepo.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (bases.isEmpty()) return List.of();
        return recommend(bases, limit);
    }

    private List<Product> recommend(List<Product> bases, int limit) {
        Set<Long> baseIds = bases.stream().map(Product::getId).collect(Collectors.toSet());
        Set<Long> inStock = new HashSet<>(offerRepo.findInStockProductIds());

        // Gate NSO: se filtra el POOL (no el resultado) para que el relleno complete el limite con perfumes visibles.
        // Gate apagado: isPublic equivale al filtro de disponibles de siempre.
        List<Product> candidates = productRepo.findByArchivedFalse().stream()
                .filter(p -> !baseIds.contains(p.getId()))
                .filter(p -> !Boolean.FALSE.equals(p.getAvailable()))
                .filter(p -> p.getImageUrl() != null && !p.getImageUrl().isBlank())
                .filter(nsoGate::isPublic)
                .collect(Collectors.toList());
        Map<Long, Product> byId = candidates.stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));

        Map<Long, Double> score = new HashMap<>();

        // 1) Co-compra real (peso dominante)
        for (Product base : bases) {
            for (Object[] row : orderItemRepo.findCoPurchasedProductIds(base.getId())) {
                Long pid = ((Number) row[0]).longValue();
                long cnt = ((Number) row[1]).longValue();
                if (byId.containsKey(pid)) score.merge(pid, 1000.0 * cnt, Double::sum);
            }
        }

        // 2) Similaridad por contenido (max sobre los bases)
        for (Product cand : candidates) {
            double best = 0;
            for (Product base : bases) best = Math.max(best, contentSimilarity(base, cand));
            if (best > 0) score.merge(cand.getId(), best, Double::sum);
        }

        List<Product> ranked = score.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.getValue(), a.getValue());
                    if (c != 0) return c;
                    boolean as = inStock.contains(a.getKey()), bs = inStock.contains(b.getKey());
                    if (as != bs) return as ? -1 : 1;
                    return 0;
                })
                .map(e -> byId.get(e.getKey()))
                .collect(Collectors.toList());

        // Relleno si quedo corto: misma marca, luego mas vendidos
        if (ranked.size() < limit) {
            Set<Long> have = ranked.stream().map(Product::getId).collect(Collectors.toSet());
            for (Product base : bases) {
                for (Product cand : candidates) {
                    if (ranked.size() >= limit) break;
                    if (!have.contains(cand.getId()) && cand.getBrand() != null
                            && cand.getBrand().equalsIgnoreCase(base.getBrand())) {
                        ranked.add(cand);
                        have.add(cand.getId());
                    }
                }
            }
            for (Object[] row : orderItemRepo.findBestSellerProductIds()) {
                if (ranked.size() >= limit) break;
                Long pid = ((Number) row[0]).longValue();
                if (byId.containsKey(pid) && !have.contains(pid)) {
                    ranked.add(byId.get(pid));
                    have.add(pid);
                }
            }
        }

        return ranked.stream().limit(limit).collect(Collectors.toList());
    }

    private double contentSimilarity(Product base, Product cand) {
        double s = 0;
        if (base.getBrand() != null && base.getBrand().equalsIgnoreCase(cand.getBrand())) s += 50;
        for (String t : significantTokens(base.getName())) {
            if (cand.getName() != null && cand.getName().toLowerCase().contains(t)) {
                s += 40;
                break;
            }
        }
        if (base.getCategory() != null && base.getCategory().equalsIgnoreCase(cand.getCategory())) s += 20;
        Double pa = base.getWholesalePricePen(), pb = cand.getWholesalePricePen();
        if (pa != null && pb != null && pa > 0) s += 25 * Math.max(0, 1 - Math.abs(pa - pb) / pa);
        if (Boolean.TRUE.equals(cand.getIsHighlighted())) s += 5;
        if (Boolean.TRUE.equals(cand.getIsNew())) s += 3;
        return s;
    }

    private static final Set<String> GENERIC = Set.of(
            "perfume", "parfum", "spray", "eau", "para", "gift", "set",
            "intense", "extrait", "unisex", "men", "women", "mujer", "hombre",
            "natural", "pour", "homme", "femme", "edition", "oriental");

    private List<String> significantTokens(String name) {
        if (name == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : name.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (t.length() >= 4 && !GENERIC.contains(t)) out.add(t);
        }
        return out;
    }
}
