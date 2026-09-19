package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.NotesImport;
import org.example.backendbvaberiaperfumes.dto.SuggestResult;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.service.PricingService;
import org.example.backendbvaberiaperfumes.service.ProductService;
import org.example.backendbvaberiaperfumes.service.RecommendationService;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.example.backendbvaberiaperfumes.service.nso.NsoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final PricingService pricingService;
    private final SupplierOfferRepository offerRepo;
    private final RecommendationService recommendationService;
    private final NsoGate nsoGate;
    private final NsoService nsoService;

    public ProductController(ProductService productService, PricingService pricingService,
                            SupplierOfferRepository offerRepo,
                            RecommendationService recommendationService, NsoGate nsoGate,
                            NsoService nsoService) {
        this.productService = productService;
        this.pricingService = pricingService;
        this.offerRepo = offerRepo;
        this.recommendationService = recommendationService;
        this.nsoGate = nsoGate;
        this.nsoService = nsoService;
    }

    /** Sugerencias para el dropdown del buscador (nombre/marca/SKU/UPC). */
    @GetMapping("/suggest")
    public List<SuggestResult> suggest(@RequestParam(required = false) String q,
                                       @RequestParam(required = false, defaultValue = "8") int limit) {
        if (q == null || q.isBlank()) return List.of();
        String query = q.toLowerCase().trim();
        return publicFilter(productService.search(q)).stream()
                .filter(p -> !Boolean.FALSE.equals(p.getAvailable()))
                .sorted((a, b) -> Integer.compare(relevance(b, query), relevance(a, query)))
                .limit(Math.max(1, Math.min(limit, 20)))
                .map(SuggestResult::from)
                .collect(Collectors.toList());
    }

    /** Mayor puntaje = mas arriba en el dropdown: nombre que empieza igual, luego marca, luego imagen. */
    private int relevance(Product p, String query) {
        int score = 0;
        String name = p.getName() == null ? "" : p.getName().toLowerCase();
        String brand = p.getBrand() == null ? "" : p.getBrand().toLowerCase();
        if (name.startsWith(query)) score += 100;
        else if (name.contains(query)) score += 50;
        if (brand.startsWith(query)) score += 30;
        else if (brand.contains(query)) score += 15;
        if (p.getImageUrl() != null && !p.getImageUrl().isBlank()) score += 10;
        if (Boolean.TRUE.equals(p.getIsHighlighted())) score += 3;
        return score;
    }

    /** Productos similares + frecuentemente pedidos juntos (hibrido). */
    @GetMapping("/{id}/related")
    public List<Product> related(@PathVariable Long id,
                                 @RequestParam(required = false, defaultValue = "8") int limit) {
        return publicFilter(recommendationService.relatedFor(id, Math.max(1, Math.min(limit, 24))));
    }

    /** Cross-sell para el carrito: recomendaciones combinadas de varios productos. */
    @GetMapping("/related")
    public List<Product> relatedForCart(@RequestParam String ids,
                                        @RequestParam(required = false, defaultValue = "8") int limit) {
        List<Long> idList = Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> {
                    try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        return publicFilter(recommendationService.crossSell(idList, Math.max(1, Math.min(limit, 24))));
    }

    @GetMapping
    public List<Product> getAll(@RequestParam(required = false) String category,
                                @RequestParam(required = false) String search,
                                @RequestParam(required = false, defaultValue = "false") boolean onlyAvailable,
                                @RequestParam(required = false, defaultValue = "false") boolean inStockOnly,
                                @RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
        List<Product> result;
        if (search != null && !search.isBlank()) result = productService.search(search);
        else if (category != null && !category.isBlank()) result = productService.getByCategory(category);
        else result = productService.getAllProducts();

        // Por defecto se ocultan los productos archivados (catalogo viejo de Crisfragance)
        if (!includeArchived) {
            result = result.stream().filter(p -> !Boolean.TRUE.equals(p.getArchived())).collect(Collectors.toList());
        }
        if (onlyAvailable) {
            result = result.stream().filter(p -> Boolean.TRUE.equals(p.getAvailable())).collect(Collectors.toList());
        }
        // Catalogo publico: mostrar solo si algun proveedor lo tiene en stock
        if (inStockOnly) {
            Set<Long> inStock = new HashSet<>(offerRepo.findInStockProductIds());
            result = result.stream().filter(p -> inStock.contains(p.getId())).collect(Collectors.toList());
        }
        return publicFilter(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getById(@PathVariable Long id) {
        return productService.getById(id)
                .filter(p -> isAdminRequest() || nsoGate.isPublicId(p.getId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** El gate solo afecta peticiones anonimas: el ERP conserva acceso al catalogo completo. */
    private List<Product> publicFilter(List<Product> products) {
        if (isAdminRequest()) return products;
        return nsoGate.filterPublic(products);
    }

    private boolean isAdminRequest() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    // El desglose de costos de proveedor (antes GET /api/products/{id}/pricing, publico)
    // se movio a GET /api/admin/products/{id}/pricing (AdminController): expone costos USD.

    // --- Admin endpoints ---
    @PostMapping
    public Product create(@RequestBody Product product) {
        Product saved = productService.save(product);
        // NSO: un perfume nuevo se verifica al instante (sin lista NSO cargada no escribe nada).
        rematchNso(saved.getId());
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable Long id, @RequestBody Product product) {
        return productService.getById(id).map(existing -> {
            String identityBefore = nsoIdentity(existing);
            if (product.getName() != null) existing.setName(product.getName());
            if (product.getBrand() != null) existing.setBrand(product.getBrand());
            if (product.getPriceUsd() != null) existing.setPriceUsd(product.getPriceUsd());
            if (product.getRetailPricePen() != null) existing.setRetailPricePen(product.getRetailPricePen());
            if (product.getWholesalePricePen() != null) existing.setWholesalePricePen(product.getWholesalePricePen());
            if (product.getMayorPricePen() != null) existing.setMayorPricePen(product.getMayorPricePen());
            if (product.getAvailable() != null) existing.setAvailable(product.getAvailable());
            if (product.getImageUrl() != null) existing.setImageUrl(product.getImageUrl());
            if (product.getDescription() != null) existing.setDescription(product.getDescription());
            if (product.getIsNew() != null) existing.setIsNew(product.getIsNew());
            if (product.getIsHighlighted() != null) existing.setIsHighlighted(product.getIsHighlighted());
            if (product.getWeightG() != null) existing.setWeightG(product.getWeightG());
            if (product.getMl() != null) existing.setMl(product.getMl());
            if (product.getType() != null) existing.setType(product.getType());
            if (product.getCategory() != null) existing.setCategory(product.getCategory());
            if (product.getStockPricePen() != null) existing.setStockPricePen(product.getStockPricePen());
            if (product.getPriceLocked() != null) existing.setPriceLocked(product.getPriceLocked());
            Product saved = productService.save(existing);
            // NSO: si cambio algo que identifica al perfume (marca, nombre, ml, tipo, categoria, codigo) se re-verifica.
            if (!Objects.equals(identityBefore, nsoIdentity(saved))) rematchNso(saved.getId());
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Lo que usa el matcher NSO para reconocer un perfume (si cambia, su NSO puede cambiar). */
    private static String nsoIdentity(Product p) {
        return String.join("|", String.valueOf(p.getBrand()), String.valueOf(p.getName()), String.valueOf(p.getMl()),
                String.valueOf(p.getType()), String.valueOf(p.getCategory()), String.valueOf(p.getGtin()));
    }

    private void rematchNso(Long productId) {
        if (productId == null) return;
        nsoService.runAfterCommit("NSO del perfume #" + productId, () -> nsoService.rematchProducts(List.of(productId)));
    }

    /** Importacion masiva de notas olfativas (generada offline por tools/build_notes_dataset.py). */
    @PutMapping("/notes/bulk")
    public Map<String, Object> importNotes(@RequestBody List<NotesImport> items) {
        int updated = 0;
        for (NotesImport it : items) {
            if (it.getId() == null) continue;
            var op = productService.getById(it.getId());
            if (op.isEmpty()) continue;
            Product p = op.get();
            p.setNotesTop(joinCsv(it.getNotesTop()));
            p.setNotesMiddle(joinCsv(it.getNotesMiddle()));
            p.setNotesBase(joinCsv(it.getNotesBase()));
            p.setOlfactiveFamily(it.getFamily());
            p.setOccasion(it.getOccasion());
            p.setSeasons(joinCsv(it.getSeasons()));
            productService.save(p);
            updated++;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("received", items.size());
        result.put("updated", updated);
        return result;
    }

    private String joinCsv(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return String.join(",", values);
    }

    @PutMapping("/{id}/prices")
    public ResponseEntity<Product> updatePrices(@PathVariable Long id, @RequestBody Map<String, Object> prices) {
        Double retailPricePen = prices.get("retailPricePen") != null ? ((Number) prices.get("retailPricePen")).doubleValue() : null;
        Double wholesalePricePen = prices.get("wholesalePricePen") != null ? ((Number) prices.get("wholesalePricePen")).doubleValue() : null;
        Double mayorPricePen = prices.get("mayorPricePen") != null ? ((Number) prices.get("mayorPricePen")).doubleValue() : null;
        Double priceUsd = prices.get("priceUsd") != null ? ((Number) prices.get("priceUsd")).doubleValue() : null;
        Integer weightG = prices.get("weightG") != null ? ((Number) prices.get("weightG")).intValue() : null;
        Product updated = productService.updatePrices(id, retailPricePen, wholesalePricePen, mayorPricePen, priceUsd, weightG);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
