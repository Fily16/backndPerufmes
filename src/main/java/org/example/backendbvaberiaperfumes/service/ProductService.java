package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.OrderItemRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepo;
    private final OrderItemRepository orderItemRepo;

    public ProductService(ProductRepository productRepo, OrderItemRepository orderItemRepo) {
        this.productRepo = productRepo;
        this.orderItemRepo = orderItemRepo;
    }

    public List<Product> getAllProducts() {
        return productRepo.findAll();
    }

    public List<Product> getAvailableProducts() {
        return productRepo.findByAvailableTrue();
    }

    public Optional<Product> getById(Long id) {
        return productRepo.findById(id);
    }

    public Optional<Product> getBySku(String sku) {
        return productRepo.findBySku(sku);
    }

    public List<Product> getByCategory(String category) {
        return productRepo.findByCategory(category);
    }

    /**
     * Busqueda flexible: tokeniza la consulta y exige que TODOS los tokens aparezcan
     * en algun campo del producto (nombre, marca, SKU o codigo de barras GTIN/UPC).
     * Asi "lattafa khamrah", un UPC pegado o un SKU encuentran el producto. Solo
     * catalogo vigente (no archivado). El filtrado en memoria es suficiente para ~1.5k productos.
     */
    public List<Product> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String[] tokens = query.toLowerCase().trim().split("\\s+");
        return productRepo.findByArchivedFalse().stream()
                .filter(p -> matchesAllTokens(p, tokens))
                .collect(java.util.stream.Collectors.toList());
    }

    private boolean matchesAllTokens(Product p, String[] tokens) {
        String haystack = (
                safe(p.getName()) + " " + safe(p.getBrand()) + " " +
                safe(p.getSku()) + " " + safe(p.getGtin())
        ).toLowerCase();
        for (String t : tokens) {
            if (!haystack.contains(t)) return false;
        }
        return true;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    public Product save(Product product) {
        return productRepo.save(product);
    }

    public Product updatePrices(Long id, Double retailPricePen, Double wholesalePricePen, Double mayorPricePen, Double priceUsd, Integer weightG) {
        Product product = productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        if (retailPricePen != null) product.setRetailPricePen(retailPricePen);
        if (wholesalePricePen != null) product.setWholesalePricePen(wholesalePricePen);
        if (mayorPricePen != null) product.setMayorPricePen(mayorPricePen);
        if (priceUsd != null) product.setPriceUsd(priceUsd);
        if (weightG != null) product.setWeightG(weightG);
        // Un precio editado a mano queda bloqueado: el recalculo automatico (import/proveedor) no lo pisa.
        if (retailPricePen != null || wholesalePricePen != null || mayorPricePen != null) {
            product.setPriceLocked(true);
        }
        return productRepo.save(product);
    }

    @Transactional
    public void delete(Long id) {
        orderItemRepo.deleteByProductId(id);
        productRepo.deleteById(id);
    }

    /**
     * Cutover no destructivo: archiva el catalogo viejo (productos sin ninguna oferta de proveedor).
     * No borra -> los pedidos/ventas historicas siguen resolviendo. Idempotente y seguro
     * sin importar si se corre antes o despues de importar (los nuevos tienen ofertas).
     */
    @Transactional
    public int archiveLegacy() {
        List<Product> legacy = productRepo.findActiveWithoutOffers();
        for (Product p : legacy) {
            p.setArchived(true);
            p.setAvailable(false);
        }
        productRepo.saveAll(legacy);
        return legacy.size();
    }
}
