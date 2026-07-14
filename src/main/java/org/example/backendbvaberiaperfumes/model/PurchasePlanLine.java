package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/** Linea de un plan de compra: producto -> proveedor elegido, con su costo real. */
@Entity
@Table(name = "purchase_plan_lines")
public class PurchasePlanLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    @JsonIgnore
    private PurchasePlan plan;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(nullable = false)
    private Integer qty;

    @Column(name = "unit_cost_usd")
    private Double unitCostUsd;

    /** Se asigno a este proveedor solo para cumplir su minimo (no era el mas barato). */
    @Column(name = "moved_to_reach_min", nullable = false, columnDefinition = "boolean default false")
    private Boolean movedToReachMin = false;

    /** Sobrecosto unitario vs el proveedor mas barato elegible. */
    @Column(name = "penalty_usd")
    private Double penaltyUsd;

    @Column(length = 200)
    private String reason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PurchasePlan getPlan() { return plan; }
    public void setPlan(PurchasePlan plan) { this.plan = plan; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public Integer getQty() { return qty; }
    public void setQty(Integer qty) { this.qty = qty; }
    public Double getUnitCostUsd() { return unitCostUsd; }
    public void setUnitCostUsd(Double unitCostUsd) { this.unitCostUsd = unitCostUsd; }
    public Boolean getMovedToReachMin() { return movedToReachMin; }
    public void setMovedToReachMin(Boolean movedToReachMin) { this.movedToReachMin = movedToReachMin; }
    public Double getPenaltyUsd() { return penaltyUsd; }
    public void setPenaltyUsd(Double penaltyUsd) { this.penaltyUsd = penaltyUsd; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
