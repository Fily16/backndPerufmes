package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.NsoEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NsoEventRepository extends JpaRepository<NsoEvent, Long> {

    List<NsoEvent> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<NsoEvent> findByProductIdOrderByCreatedAtDescIdDesc(Long productId, Pageable pageable);

    List<NsoEvent> findByTypeOrderByCreatedAtDescIdDesc(String type, Pageable pageable);

    List<NsoEvent> findByProductIdAndTypeOrderByCreatedAtDescIdDesc(Long productId, String type, Pageable pageable);

    /** Ultimo evento de un tipo (ej. CATALOG_UPLOAD para summary.lastUpload). */
    Optional<NsoEvent> findFirstByTypeOrderByCreatedAtDescIdDesc(String type);

    /** GET /events?productId=&type=&limit=: filtros opcionales (null/blank = sin filtro), mas nuevos primero. */
    default List<NsoEvent> recent(Long productId, String type, int limit) {
        Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit <= 0 ? 200 : limit, 1000)));
        boolean hasType = type != null && !type.isBlank();
        if (productId != null && hasType) return findByProductIdAndTypeOrderByCreatedAtDescIdDesc(productId, type.trim(), page);
        if (productId != null) return findByProductIdOrderByCreatedAtDescIdDesc(productId, page);
        if (hasType) return findByTypeOrderByCreatedAtDescIdDesc(type.trim(), page);
        return findAllByOrderByCreatedAtDescIdDesc(page);
    }
}
