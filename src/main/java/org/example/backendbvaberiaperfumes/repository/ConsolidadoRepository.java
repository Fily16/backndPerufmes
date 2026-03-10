package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ConsolidadoRepository extends JpaRepository<Consolidado, Long> {
    List<Consolidado> findByStatus(String status);
    Optional<Consolidado> findFirstByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
}
