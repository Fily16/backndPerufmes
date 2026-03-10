package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consolidado_id")
    @JsonIgnore
    private Consolidado consolidado;

    @Column(nullable = false)
    private String clientName;

    @Column(nullable = false)
    private String clientPhone;

    @Column(nullable = false)
    private String paymentStatus = "PENDIENTE_SEPARACION";

    @Column(unique = true)
    private String orderCode;

    private String yapeReference;
    private Double totalPen = 0.0;
    private Double depositAmountPen = 0.0;
    private Double remainingPen = 0.0;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Order() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public void recalculateTotal() {
        this.totalPen = items.stream()
                .mapToDouble(i -> i.getSubtotalPen() != null ? i.getSubtotalPen() : 0)
                .sum();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Consolidado getConsolidado() { return consolidado; }
    public void setConsolidado(Consolidado consolidado) { this.consolidado = consolidado; }

    public Long getConsolidadoId() { return consolidado != null ? consolidado.getId() : null; }

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getClientPhone() { return clientPhone; }
    public void setClientPhone(String clientPhone) { this.clientPhone = clientPhone; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getOrderCode() { return orderCode; }
    public void setOrderCode(String orderCode) { this.orderCode = orderCode; }

    public String getYapeReference() { return yapeReference; }
    public void setYapeReference(String yapeReference) { this.yapeReference = yapeReference; }

    public Double getDepositAmountPen() { return depositAmountPen; }
    public void setDepositAmountPen(Double depositAmountPen) { this.depositAmountPen = depositAmountPen; }

    public Double getRemainingPen() { return remainingPen; }
    public void setRemainingPen(Double remainingPen) { this.remainingPen = remainingPen; }

    public Double getTotalPen() { return totalPen; }
    public void setTotalPen(Double totalPen) { this.totalPen = totalPen; }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
