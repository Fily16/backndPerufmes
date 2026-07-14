package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.PurchasePlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchasePlanRepository extends JpaRepository<PurchasePlan, Long> {

    Optional<PurchasePlan> findFirstByConsolidadoIdAndStatusOrderByCreatedAtDesc(Long consolidadoId, String status);

    List<PurchasePlan> findByConsolidadoIdAndStatus(Long consolidadoId, String status);
}
