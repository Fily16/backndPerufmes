package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Promoción / pack: 1..N perfumes (del catálogo o exclusivos de la promo) vendidos
 * juntos a un precio de pack, con stock propio independiente del inventario retail.
 */
@Entity
@Table(name = "promotions")
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Imagen de la publicación por URL (opcional / legado). */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /** Imagen de la publicación subida (data URL base64). Preferida sobre imageUrl. */
    @Column(name = "image_data", columnDefinition = "text")
    private String imageData;

    /** Precio del pack completo en soles. */
    @Column(name = "price_pen", nullable = false)
    private Double pricePen = 0.0;

    /** Ganancia del pack: sugerida (precio - Σ puesto en Perú) o manual. */
    @Column(name = "profit_pen", nullable = false)
    private Double profitPen = 0.0;

    /** Packs disponibles (stock propio de la promo). */
    @Column(name = "stock_qty", nullable = false)
    private Integer stockQty = 0;

    /** Vigencia: último día en que la promo se muestra/compra. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(nullable = false)
    private Boolean active = true;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PromotionItem> items = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Promotion() {}

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getImageData() { return imageData; }
    public void setImageData(String imageData) { this.imageData = imageData; }

    public Double getPricePen() { return pricePen; }
    public void setPricePen(Double pricePen) { this.pricePen = pricePen; }

    public Double getProfitPen() { return profitPen; }
    public void setProfitPen(Double profitPen) { this.profitPen = profitPen; }

    public Integer getStockQty() { return stockQty; }
    public void setStockQty(Integer stockQty) { this.stockQty = stockQty; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public List<PromotionItem> getItems() { return items; }
    public void setItems(List<PromotionItem> items) { this.items = items; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
