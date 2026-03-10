package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.RetailSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface RetailSaleRepository extends JpaRepository<RetailSale, Long> {
    List<RetailSale> findByProductId(Long productId);

    @Query("SELECT COALESCE(SUM(rs.salePricePen * rs.quantity), 0) FROM RetailSale rs")
    double sumTotalRevenue();

    @Query("SELECT COALESCE(SUM(rs.profitPen), 0) FROM RetailSale rs")
    double sumTotalProfit();
}
