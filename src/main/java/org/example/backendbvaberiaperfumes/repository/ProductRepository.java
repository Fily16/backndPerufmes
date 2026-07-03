package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);
    Optional<Product> findByGtin(String gtin);
    List<Product> findByAvailableTrue();
    List<Product> findByCategory(String category);
    List<Product> findByBrand(String brand);
    List<Product> findByNameContainingIgnoreCaseOrBrandContainingIgnoreCase(String name, String brand);
    boolean existsBySku(String sku);

    // --- Multi-proveedor / catalogo nuevo ---
    List<Product> findByArchivedFalse();
    List<Product> findByArchivedFalseAndAvailableTrue();
    long countByArchivedFalse();

    /** Productos activos del catalogo viejo (sin ninguna oferta de proveedor) -> a archivar en el cutover. */
    @Query("select p from Product p where p.archived = false and p.id not in (select o.product.id from SupplierOffer o)")
    List<Product> findActiveWithoutOffers();
}
