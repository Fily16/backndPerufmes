package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "consolidados")
public class Consolidado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String status;

    @Column(name = "open_date")
    private LocalDateTime openDate;

    @Column(name = "close_date")
    private LocalDateTime closeDate;

    @Column(name = "total_weight_g", nullable = false)
    private Integer totalWeightG = 0;

    @Column(name = "total_cost_usd", nullable = false)
    private Double totalCostUsd = 0.0;

    @Column(name = "courier_cost_usd", nullable = false)
    private Double courierCostUsd = 0.0;

    @Column(name = "total_investment_pen", nullable = false)
    private Double totalInvestmentPen = 0.0;

    @Column(name = "total_revenue_pen", nullable = false)
    private Double totalRevenuePen = 0.0;

    @Column(name = "projected_profit_pen", nullable = false)
    private Double projectedProfitPen = 0.0;

    @Column(length = 2000)
    private String notes;

    @OneToMany(mappedBy = "consolidado", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Order> orders = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Consolidado() {
    }

    public Consolidado(Long id, String status, LocalDateTime openDate, LocalDateTime closeDate,
                       Integer totalWeightG, Double totalCostUsd, Double courierCostUsd,
                       Double totalInvestmentPen, Double totalRevenuePen, Double projectedProfitPen,
                       String notes, List<Order> orders, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.status = status;
        this.openDate = openDate;
        this.closeDate = closeDate;
        this.totalWeightG = totalWeightG;
        this.totalCostUsd = totalCostUsd;
        this.courierCostUsd = courierCostUsd;
        this.totalInvestmentPen = totalInvestmentPen;
        this.totalRevenuePen = totalRevenuePen;
        this.projectedProfitPen = projectedProfitPen;
        this.notes = notes;
        this.orders = orders;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getOpenDate() {
        return openDate;
    }

    public void setOpenDate(LocalDateTime openDate) {
        this.openDate = openDate;
    }

    public LocalDateTime getCloseDate() {
        return closeDate;
    }

    public void setCloseDate(LocalDateTime closeDate) {
        this.closeDate = closeDate;
    }

    public Integer getTotalWeightG() {
        return totalWeightG;
    }

    public void setTotalWeightG(Integer totalWeightG) {
        this.totalWeightG = totalWeightG;
    }

    public Double getTotalCostUsd() {
        return totalCostUsd;
    }

    public void setTotalCostUsd(Double totalCostUsd) {
        this.totalCostUsd = totalCostUsd;
    }

    public Double getCourierCostUsd() {
        return courierCostUsd;
    }

    public void setCourierCostUsd(Double courierCostUsd) {
        this.courierCostUsd = courierCostUsd;
    }

    public Double getTotalInvestmentPen() {
        return totalInvestmentPen;
    }

    public void setTotalInvestmentPen(Double totalInvestmentPen) {
        this.totalInvestmentPen = totalInvestmentPen;
    }

    public Double getTotalRevenuePen() {
        return totalRevenuePen;
    }

    public void setTotalRevenuePen(Double totalRevenuePen) {
        this.totalRevenuePen = totalRevenuePen;
    }

    public Double getProjectedProfitPen() {
        return projectedProfitPen;
    }

    public void setProjectedProfitPen(Double projectedProfitPen) {
        this.projectedProfitPen = projectedProfitPen;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public void setOrders(List<Order> orders) {
        this.orders = orders;
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
        Consolidado that = (Consolidado) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Consolidado{" +
                "id=" + id +
                ", status='" + status + '\'' +
                ", openDate=" + openDate +
                ", closeDate=" + closeDate +
                ", totalWeightG=" + totalWeightG +
                ", totalCostUsd=" + totalCostUsd +
                ", courierCostUsd=" + courierCostUsd +
                ", totalInvestmentPen=" + totalInvestmentPen +
                ", totalRevenuePen=" + totalRevenuePen +
                ", projectedProfitPen=" + projectedProfitPen +
                ", notes='" + notes + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
