package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "retail_sales")
public class RetailSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    private Integer quantity = 1;

    @Column(nullable = false)
    private Double salePricePen;

    private Double costPen;
    private Double profitPen;
    private String channel = "WHATSAPP";
    private LocalDateTime saleDate;
    private LocalDateTime createdAt;

    public RetailSale() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (saleDate == null) saleDate = LocalDateTime.now();
        if (costPen != null && salePricePen != null) {
            profitPen = (salePricePen - costPen) * (quantity != null ? quantity : 1);
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Double getSalePricePen() { return salePricePen; }
    public void setSalePricePen(Double salePricePen) { this.salePricePen = salePricePen; }
    public Double getCostPen() { return costPen; }
    public void setCostPen(Double costPen) { this.costPen = costPen; }
    public Double getProfitPen() { return profitPen; }
    public void setProfitPen(Double profitPen) { this.profitPen = profitPen; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public LocalDateTime getSaleDate() { return saleDate; }
    public void setSaleDate(LocalDateTime saleDate) { this.saleDate = saleDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
