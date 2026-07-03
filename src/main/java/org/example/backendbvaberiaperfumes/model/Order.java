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

    // --- NUEVOS CAMPOS DE ENVÍO ---
    private String deliveryMethod; // "LIMA" o "PROVINCIA"
    private String shippingName;
    private String shippingDni;
    private String shippingPhone;
    private String shippingAddress;
    private String shippingDepartment; // Departamento de destino (envío Shalom a provincia)
    private String shippingAgency;     // Sede/agencia Shalom elegida

    // --- Vendedor que gestiona el pedido (ERP) ---
    private String attendedBy;       // nombre del admin que atendió
    private Long attendedById;       // id del admin que atendió

    /** Canal del pedido: CONSOLIDADO (por encargo) o STOCK (compra de stock de tienda). */
    @Column(name = "channel")
    private String channel = "CONSOLIDADO";

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderPromo> promos = new ArrayList<>();

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
        double itemsTotal = items.stream()
                .mapToDouble(i -> i.getSubtotalPen() != null ? i.getSubtotalPen() : 0)
                .sum();
        double promosTotal = promos == null ? 0 : promos.stream()
                .mapToDouble(p -> p.getSubtotalPen() != null ? p.getSubtotalPen() : 0)
                .sum();
        this.totalPen = itemsTotal + promosTotal;
    }

    // Getters y Setters
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

    // --- Getters y Setters de Envío ---
    public String getDeliveryMethod() { return deliveryMethod; }
    public void setDeliveryMethod(String deliveryMethod) { this.deliveryMethod = deliveryMethod; }

    public String getShippingName() { return shippingName; }
    public void setShippingName(String shippingName) { this.shippingName = shippingName; }

    public String getShippingDni() { return shippingDni; }
    public void setShippingDni(String shippingDni) { this.shippingDni = shippingDni; }

    public String getShippingPhone() { return shippingPhone; }
    public void setShippingPhone(String shippingPhone) { this.shippingPhone = shippingPhone; }

    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String shippingAddress) { this.shippingAddress = shippingAddress; }

    public String getShippingDepartment() { return shippingDepartment; }
    public void setShippingDepartment(String shippingDepartment) { this.shippingDepartment = shippingDepartment; }

    public String getShippingAgency() { return shippingAgency; }
    public void setShippingAgency(String shippingAgency) { this.shippingAgency = shippingAgency; }

    public String getAttendedBy() { return attendedBy; }
    public void setAttendedBy(String attendedBy) { this.attendedBy = attendedBy; }

    public Long getAttendedById() { return attendedById; }
    public void setAttendedById(Long attendedById) { this.attendedById = attendedById; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public List<OrderPromo> getPromos() { return promos; }
    public void setPromos(List<OrderPromo> promos) { this.promos = promos; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}