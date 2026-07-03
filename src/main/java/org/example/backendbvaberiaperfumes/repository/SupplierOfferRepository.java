package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SupplierOfferRepository extends JpaRepository<SupplierOffer, Long> {
    Optional<SupplierOffer> findBySupplier_IdAndOfferKey(Long supplierId, String offerKey);
    List<SupplierOffer> findBySupplier_Id(Long supplierId);
    List<SupplierOffer> findByProduct_Id(Long productId);
    List<SupplierOffer> findByProduct_IdAndInStockTrue(Long productId);
    long countByInStockTrue();

    @Query("select distinct o.product.id from SupplierOffer o where o.inStock = true")
    List<Long> findInStockProductIds();
}
