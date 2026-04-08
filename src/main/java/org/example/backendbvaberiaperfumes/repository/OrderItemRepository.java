package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByProductId(Long productId);

    @Modifying
    @Query("UPDATE OrderItem oi SET oi.product = null WHERE oi.product.id = :productId")
    void nullifyProductReferences(@Param("productId") Long productId);
}
