package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.MissingResolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MissingResolutionRepository extends JpaRepository<MissingResolution, Long> {
    List<MissingResolution> findByConsolidadoId(Long consolidadoId);
    Optional<MissingResolution> findByConsolidadoIdAndProductId(Long consolidadoId, Long productId);
}
