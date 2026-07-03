package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.PromotionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromotionItemRepository extends JpaRepository<PromotionItem, Long> {
    List<PromotionItem> findByProductId(Long productId);
}
