package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepo;

    public ProductService(ProductRepository productRepo) {
        this.productRepo = productRepo;
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

    public List<Product> search(String query) {
        return productRepo.findByNameContainingIgnoreCaseOrBrandContainingIgnoreCase(query, query);
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
        return productRepo.save(product);
    }

    public void delete(Long id) {
        productRepo.deleteById(id);
    }
}
