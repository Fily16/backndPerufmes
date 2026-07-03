package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;

@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    /** Compra minima para precios mayoristas. 0 = sin minimo (ej. Magnet). */
    @Column(nullable = false)
    private Double minOrderUsd = 0.0;

    @Column(nullable = false)
    private String currency = "USD";

    @Column(nullable = false)
    private Boolean active = true;

    /** Si true, el motor de asignacion prioriza llenar este proveedor hasta su minimo (ej. Zimaxx $2000). */
    @Column(nullable = false)
    private Boolean priorityToReachMin = false;

    public Supplier() {}

    public Supplier(String name, Double minOrderUsd, Boolean priorityToReachMin) {
        this.name = name;
        this.minOrderUsd = minOrderUsd;
        this.priorityToReachMin = priorityToReachMin;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getMinOrderUsd() { return minOrderUsd; }
    public void setMinOrderUsd(Double minOrderUsd) { this.minOrderUsd = minOrderUsd; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public Boolean getPriorityToReachMin() { return priorityToReachMin; }
    public void setPriorityToReachMin(Boolean priorityToReachMin) { this.priorityToReachMin = priorityToReachMin; }
}
