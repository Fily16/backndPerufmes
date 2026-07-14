package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;

/**
 * Restriccion de compra de un proveedor, como DATO (no como if-else en el codigo).
 * Agregar un proveedor nuevo = crear filas aqui; agregar un TIPO nuevo de restriccion
 * = una clase ConstraintEvaluator; el motor de asignacion no cambia.
 *
 * Tipos soportados:
 *  - MIN_ORDER_USD:       pedido minimo en USD (Zimaxx: 2000).
 *  - MIN_UNITS:           minimo de unidades sin importar cuales (FragranceSense: 48).
 *  - MIN_UNITS_PER_BRAND: minimo de unidades de una marca (scopeJson: {"brand":"Lattafa"}).
 */
@Entity
@Table(name = "supplier_constraints")
public class SupplierConstraint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(name = "value_num", nullable = false)
    private Double valueNum;

    /** Alcance opcional en JSON, ej. {"brand":"Lattafa"} para MIN_UNITS_PER_BRAND. */
    @Column(name = "scope_json", length = 500)
    private String scopeJson;

    @Column(nullable = false)
    private Boolean active = true;

    public SupplierConstraint() {}

    public SupplierConstraint(Supplier supplier, String type, Double valueNum) {
        this.supplier = supplier;
        this.type = type;
        this.valueNum = valueNum;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier supplier) { this.supplier = supplier; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Double getValueNum() { return valueNum; }
    public void setValueNum(Double valueNum) { this.valueNum = valueNum; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
