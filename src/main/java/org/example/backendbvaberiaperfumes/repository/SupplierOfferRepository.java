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
    /** Solo ofertas en stock de proveedores ACTIVOS (para precios y asignacion de compra). */
    List<SupplierOffer> findByProduct_IdAndInStockTrueAndSupplier_ActiveTrue(Long productId);
    long countByInStockTrue();

    /** IDs de productos que tienen oferta de este proveedor (para el ripple al desactivar/eliminar). */
    @Query("select distinct o.product.id from SupplierOffer o where o.supplier.id = :supplierId")
    List<Long> findProductIdsBySupplier(Long supplierId);

    void deleteBySupplier_Id(Long supplierId);

    @Query("select distinct o.product.id from SupplierOffer o where o.inStock = true")
    List<Long> findInStockProductIds();
}
