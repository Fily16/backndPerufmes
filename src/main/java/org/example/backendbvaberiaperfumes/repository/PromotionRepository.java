package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    List<Promotion> findByActiveTrue();

    /** Promos que se muestran en la tienda: activas, con stock y vigentes. */
    @Query("select p from Promotion p where p.active = true and p.stockQty > 0 " +
           "and (p.validUntil is null or p.validUntil >= :today) order by p.createdAt desc")
    List<Promotion> findActiveForStore(@Param("today") LocalDate today);
}
