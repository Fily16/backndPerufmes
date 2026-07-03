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

    /** Precio de venta inmediata cuando el perfume se "lanza" a stock de tienda (= costo landed + S/35). */
    @Column(name = "stock_price_pen")
    private Double stockPricePen;

    /** Si true, el precio se editó manualmente y NO se recalcula al cambiar T/C o courier. */
    @Column(name = "price_locked", nullable = false, columnDefinition = "boolean default false")
    private Boolean priceLocked = false;

    @Column(length = 1000)
    private String description;

    @Column
    private String category;

    @Column(name = "is_new", nullable = false)
    private Boolean isNew = false;

    @Column(name = "is_highlighted", nullable = false)
    private Boolean isHighlighted = false;

    // --- Multi-proveedor ---
    /** GTIN-14 canonico confiable. Null si el producto no tiene codigo confiable o es colision. */
    @Column(name = "gtin")
    private String gtin;

    /** single | set | deo | oil */
    @Column(name = "forma")
    private String forma = "single";

    /** Productos archivados (catalogo viejo de Crisfragance) no se muestran al cliente. */
    @Column(name = "archived", nullable = false, columnDefinition = "boolean default false")
    private Boolean archived = false;

    /** true cuando el GTIN choca con otro producto distinto (codigo mal puesto por el proveedor). */
    @Column(name = "gtin_conflict", nullable = false, columnDefinition = "boolean default false")
    private Boolean gtinConflict = false;

    // --- Notas olfativas (slugs canonicos unidos por coma; ver note-catalog.ts en el frontend) ---
    @Column(name = "notes_top", length = 500)
    private String notesTop;

    @Column(name = "notes_middle", length = 500)
    private String notesMiddle;

    @Column(name = "notes_base", length = 500)
    private String notesBase;

    /** Familia olfativa dominante: citrico|floral|dulce|especiado|amaderado|ambar|aromatico|frutal|almizcle|cuero */
    @Column(name = "olfactive_family", length = 40)
    private String olfactiveFamily;

    /** Ocasion sugerida (derivada de las notas): dia|noche|versatil */
    @Column(name = "occasion", length = 20)
    private String occasion;

    /** Estaciones sugeridas, unidas por coma: primavera,verano,otono,invierno */
    @Column(name = "seasons", length = 80)
    private String seasons;

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

    public Double getStockPricePen() {
        return stockPricePen;
    }

    public void setStockPricePen(Double stockPricePen) {
        this.stockPricePen = stockPricePen;
    }

    public Boolean getPriceLocked() {
        return priceLocked;
    }

    public void setPriceLocked(Boolean priceLocked) {
        this.priceLocked = priceLocked;
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

    public String getGtin() {
        return gtin;
    }

    public void setGtin(String gtin) {
        this.gtin = gtin;
    }

    public String getForma() {
        return forma;
    }

    public void setForma(String forma) {
        this.forma = forma;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Boolean getGtinConflict() {
        return gtinConflict;
    }

    public void setGtinConflict(Boolean gtinConflict) {
        this.gtinConflict = gtinConflict;
    }

    public String getNotesTop() {
        return notesTop;
    }

    public void setNotesTop(String notesTop) {
        this.notesTop = notesTop;
    }

    public String getNotesMiddle() {
        return notesMiddle;
    }

    public void setNotesMiddle(String notesMiddle) {
        this.notesMiddle = notesMiddle;
    }

    public String getNotesBase() {
        return notesBase;
    }

    public void setNotesBase(String notesBase) {
        this.notesBase = notesBase;
    }

    public String getOlfactiveFamily() {
        return olfactiveFamily;
    }

    public void setOlfactiveFamily(String olfactiveFamily) {
        this.olfactiveFamily = olfactiveFamily;
    }

    public String getOccasion() {
        return occasion;
    }

    public void setOccasion(String occasion) {
        this.occasion = occasion;
    }

    public String getSeasons() {
        return seasons;
    }

    public void setSeasons(String seasons) {
        this.seasons = seasons;
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
