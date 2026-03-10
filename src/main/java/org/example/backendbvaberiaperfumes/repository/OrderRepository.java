package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByConsolidado_Id(Long consolidadoId);
    List<Order> findByPaymentStatus(String paymentStatus);
    long countByPaymentStatus(String paymentStatus);
    List<Order> findByClientPhone(String clientPhone);
    Optional<Order> findByOrderCode(String orderCode);
}
