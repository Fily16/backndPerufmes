package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan de compra calculado por el optimizador para un consolidado: que producto
 * se compra a que proveedor y a que costo REAL. Al confirmarse, la ganancia del
 * consolidado se calcula contra este plan (no contra el costo asumido al publicar).
 *
 * status: DRAFT (calculado) -> CONFIRMED (el admin lo valido) | SUPERSEDED (reemplazado).
 */
@Entity
@Table(name = "purchase_plans")
public class PurchasePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consolidado_id", nullable = false)
    private Long consolidadoId;

    @Column(nullable = false, length = 12)
    private String status = "DRAFT";

    @Column(name = "baseline_total_usd")
    private Double baselineTotalUsd;

    @Column(name = "total_usd")
    private Double totalUsd;

    @Column(name = "extra_cost_usd")
    private Double extraCostUsd;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PurchasePlanLine> lines = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "DRAFT";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConsolidadoId() { return consolidadoId; }
    public void setConsolidadoId(Long consolidadoId) { this.consolidadoId = consolidadoId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getBaselineTotalUsd() { return baselineTotalUsd; }
    public void setBaselineTotalUsd(Double baselineTotalUsd) { this.baselineTotalUsd = baselineTotalUsd; }
    public Double getTotalUsd() { return totalUsd; }
    public void setTotalUsd(Double totalUsd) { this.totalUsd = totalUsd; }
    public Double getExtraCostUsd() { return extraCostUsd; }
    public void setExtraCostUsd(Double extraCostUsd) { this.extraCostUsd = extraCostUsd; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public List<PurchasePlanLine> getLines() { return lines; }
    public void setLines(List<PurchasePlanLine> lines) { this.lines = lines; }
}
