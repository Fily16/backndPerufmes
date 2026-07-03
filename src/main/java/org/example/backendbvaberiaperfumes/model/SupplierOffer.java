package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Oferta de un proveedor para un producto canonico. Muchas ofertas -> un Product.
 *
 * offerKey es la clave estable por proveedor para re-importar de forma idempotente:
 *   - normalmente = gtin (GTIN-14)
 *   - en colisiones de UPC (mismo codigo, productos distintos) = gtin + "#" + slug(nombre)
 *   - sin codigo de barras = "NOUPC#" + slug(nombre)
 */
@Entity
@Table(name = "supplier_offers",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_offerkey", columnNames = {"supplier_id", "offer_key"}))
public class SupplierOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "offer_key", nullable = false)
    private String offerKey;

    /** GTIN-14 crudo del proveedor; null o compartido en colisiones. */
    private String gtin;

    private String supplierSku;

    private Double costUsd;

    @Column(nullable = false)
    private Boolean inStock = true;

    @Column(nullable = false)
    private Boolean flashSale = false;

    private LocalDateTime lastImportedAt;

    @Column(length = 500)
    private String rawTitle;

    public SupplierOffer() {}

    @JsonIgnore
    public Long getSupplierId() { return supplier != null ? supplier.getId() : null; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier supplier) { this.supplier = supplier; }
    public String getOfferKey() { return offerKey; }
    public void setOfferKey(String offerKey) { this.offerKey = offerKey; }
    public String getGtin() { return gtin; }
    public void setGtin(String gtin) { this.gtin = gtin; }
    public String getSupplierSku() { return supplierSku; }
    public void setSupplierSku(String supplierSku) { this.supplierSku = supplierSku; }
    public Double getCostUsd() { return costUsd; }
    public void setCostUsd(Double costUsd) { this.costUsd = costUsd; }
    public Boolean getInStock() { return inStock; }
    public void setInStock(Boolean inStock) { this.inStock = inStock; }
    public Boolean getFlashSale() { return flashSale; }
    public void setFlashSale(Boolean flashSale) { this.flashSale = flashSale; }
    public LocalDateTime getLastImportedAt() { return lastImportedAt; }
    public void setLastImportedAt(LocalDateTime lastImportedAt) { this.lastImportedAt = lastImportedAt; }
    public String getRawTitle() { return rawTitle; }
    public void setRawTitle(String rawTitle) { this.rawTitle = rawTitle; }
}
