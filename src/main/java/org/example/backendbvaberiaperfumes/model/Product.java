package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sku;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String name;

    @Column
    private String type;

    @Column
    private Integer ml;

    @Column(name = "price_usd")
    private Double priceUsd;

    @Column(name = "weight_g")
    private Integer weightG;

    @Column(nullable = false)
    private Boolean available = true;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "retail_price_pen")
    private Double retailPricePen;

    @Column(name = "wholesale_price_pen")
    private Double wholesalePricePen;

    @Column(name = "mayor_price_pen")
    private Double mayorPricePen;

    @Column(length = 1000)
    private String description;

    @Column
    private String category;

    @Column(name = "is_new", nullable = false)
    private Boolean isNew = false;

    @Column(name = "is_highlighted", nullable = false)
    private Boolean isHighlighted = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Product() {
    }

    public Product(Long id, String sku, String brand, String name, String type, Integer ml,
                   Double priceUsd, Integer weightG, Boolean available, String imageUrl,
                   Double retailPricePen, Double wholesalePricePen, String description,
                   String category, Boolean isNew, Boolean isHighlighted,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.sku = sku;
        this.brand = brand;
        this.name = name;
        this.type = type;
        this.ml = ml;
        this.priceUsd = priceUsd;
        this.weightG = weightG;
        this.available = available;
        this.imageUrl = imageUrl;
        this.retailPricePen = retailPricePen;
        this.wholesalePricePen = wholesalePricePen;
        this.description = description;
        this.category = category;
        this.isNew = isNew;
        this.isHighlighted = isHighlighted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getMl() {
        return ml;
    }

    public void setMl(Integer ml) {
        this.ml = ml;
    }

    public Double getPriceUsd() {
        return priceUsd;
    }

    public void setPriceUsd(Double priceUsd) {
        this.priceUsd = priceUsd;
    }

    public Integer getWeightG() {
        return weightG;
    }

    public void setWeightG(Integer weightG) {
        this.weightG = weightG;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Double getRetailPricePen() {
        return retailPricePen;
    }

    public void setRetailPricePen(Double retailPricePen) {
        this.retailPricePen = retailPricePen;
    }

    public Double getWholesalePricePen() {
        return wholesalePricePen;
    }

    public void setWholesalePricePen(Double wholesalePricePen) {
        this.wholesalePricePen = wholesalePricePen;
    }

    public Double getMayorPricePen() {
        return mayorPricePen;
    }

    public void setMayorPricePen(Double mayorPricePen) {
        this.mayorPricePen = mayorPricePen;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getIsNew() {
        return isNew;
    }

    public void setIsNew(Boolean isNew) {
        this.isNew = isNew;
    }

    public Boolean getIsHighlighted() {
        return isHighlighted;
    }

    public void setIsHighlighted(Boolean isHighlighted) {
        this.isHighlighted = isHighlighted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Product product = (Product) o;
        return Objects.equals(id, product.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Product{" +
                "id=" + id +
                ", sku='" + sku + '\'' +
                ", brand='" + brand + '\'' +
                ", name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", ml=" + ml +
                ", priceUsd=" + priceUsd +
                ", weightG=" + weightG +
                ", available=" + available +
                ", imageUrl='" + imageUrl + '\'' +
                ", retailPricePen=" + retailPricePen +
                ", wholesalePricePen=" + wholesalePricePen +
                ", description='" + description + '\'' +
                ", category='" + category + '\'' +
                ", isNew=" + isNew +
                ", isHighlighted=" + isHighlighted +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
