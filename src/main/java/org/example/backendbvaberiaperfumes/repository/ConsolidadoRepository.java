package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ConsolidadoRepository extends JpaRepository<Consolidado, Long> {
    List<Consolidado> findByStatus(String status);
    Optional<Consolidado> findFirstByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);

    /** Ancla de pedidos STOCK: el mas nuevo sin importar status (nunca debe quedar null el FK). */
    Optional<Consolidado> findFirstByOrderByCreatedAtDesc();

    /** El mas nuevo dentro de un grupo de estados (/current, compra de tienda). */
    Optional<Consolidado> findFirstByStatusInOrderByCreatedAtDesc(List<String> statuses);

    long countByStatusIn(List<String> statuses);

    /** Anti-huerfanos: impide borrar una imagen de la galeria que un consolidado usa. */
    boolean existsByImageMediaId(Long imageMediaId);
}
