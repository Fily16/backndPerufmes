package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.RetailInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface RetailInventoryRepository extends JpaRepository<RetailInventory, Long> {
    List<RetailInventory> findByQuantityGreaterThan(int min);
    List<RetailInventory> findByProductId(Long productId);

    @Query("SELECT COALESCE(SUM(ri.quantity), 0) FROM RetailInventory ri")
    int sumTotalStock();

    @Query("SELECT COALESCE(SUM(ri.quantity), 0) FROM RetailInventory ri WHERE ri.product.id = :productId")
    int sumStockByProductId(Long productId);

    @Query("SELECT ri.product.id, SUM(ri.quantity) FROM RetailInventory ri GROUP BY ri.product.id HAVING SUM(ri.quantity) > 0")
    List<Object[]> findStockByProduct();
}
