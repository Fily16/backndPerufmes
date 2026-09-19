package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.NsoCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NsoCandidateRepository extends JpaRepository<NsoCandidate, Long> {

    @Query("select count(distinct c.productId) from NsoCandidate c, Product p "
            + "where c.productId = p.id and p.archived = false and c.status = 'PENDING'")
    long countCurrentProductsWithPending();

    boolean existsByProductIdAndNsoCode(Long productId, String nsoCode);

    Optional<NsoCandidate> findByProductIdAndNsoCode(Long productId, String nsoCode);

    List<NsoCandidate> findByProductIdOrderByRankAscIdAsc(Long productId);

    /** Todos los candidatos (cualquier estado) de un bloque de productos: dedup por (producto, codigo) en el rematch. */
    List<NsoCandidate> findByProductIdIn(Collection<Long> productIds);

    /** Candidatos PENDING de un producto, en orden de rank (lo que muestra la tarjeta "Por revisar"). */
    List<NsoCandidate> findByProductIdAndStatusOrderByRankAscIdAsc(Long productId, String status);

    List<NsoCandidate> findByProductIdInAndStatus(Collection<Long> productIds, String status);

    List<NsoCandidate> findByStatusOrderByProductIdAscRankAscIdAsc(String status);

    List<NsoCandidate> findByNsoCodeAndStatus(String nsoCode, String status);

    /** Cantidad de PRODUCTOS (no candidatos) con al menos un candidato PENDING (badge y /candidates/count). */
    @Query("select count(distinct c.productId) from NsoCandidate c where c.status = 'PENDING'")
    long countProductsWithPending();

    @Query("select distinct c.productId from NsoCandidate c where c.status = :status")
    List<Long> findProductIdsByStatus(@Param("status") String status);

    /** Pasa a SUPERSEDED los PENDING de un producto (se resolvio por otro camino). Devuelve filas tocadas. */
    @Modifying
    @Transactional
    @Query("update NsoCandidate c set c.status = 'SUPERSEDED', c.resolvedAt = :now "
            + "where c.productId = :productId and c.status = 'PENDING'")
    int supersedePending(@Param("productId") Long productId, @Param("now") LocalDateTime now);

    /** Limpieza al borrar un producto. */
    @Modifying
    @Transactional
    @Query("delete from NsoCandidate c where c.productId = :productId")
    int deleteByProductId(@Param("productId") Long productId);
}
