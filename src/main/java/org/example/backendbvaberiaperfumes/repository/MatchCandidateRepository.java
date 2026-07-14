package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.MatchCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchCandidateRepository extends JpaRepository<MatchCandidate, Long> {

    List<MatchCandidate> findByStatusOrderByCreatedAtDesc(String status);

    List<MatchCandidate> findByStatusAndKindOrderByCreatedAtDesc(String status, String kind);

    boolean existsBySourceProductIdAndTargetProductId(Long sourceProductId, Long targetProductId);

    boolean existsBySupplierOfferIdAndKind(Long supplierOfferId, String kind);

    List<MatchCandidate> findBySourceProductIdAndStatus(Long sourceProductId, String status);

    List<MatchCandidate> findByTargetProductIdAndStatus(Long targetProductId, String status);

    long countByStatus(String status);
}
