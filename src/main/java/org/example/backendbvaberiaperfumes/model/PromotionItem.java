package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * Perfume dentro de una promoción. Puede referenciar un producto del catálogo
 * (productId != null) o ser un perfume que existe SOLO dentro de la promo
 * (productId == null): en ese caso no entra a las listas globales de stock/consolidado.
 */
@Entity
@Table(name = "promotion_items")
public class PromotionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    @JsonIgnore
    private Promotion promotion;

    /** Id del producto del catálogo, o null si es un perfume exclusivo de la promo. */
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private String name;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    public PromotionItem() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Promotion getPromotion() { return promotion; }
    public void setPromotion(Promotion promotion) { this.promotion = promotion; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
