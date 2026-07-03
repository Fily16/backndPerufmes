package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.OrderPromo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderPromoRepository extends JpaRepository<OrderPromo, Long> {
    List<OrderPromo> findByOrderId(Long orderId);
}
