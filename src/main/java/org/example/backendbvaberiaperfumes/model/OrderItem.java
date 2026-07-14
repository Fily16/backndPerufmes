package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private Order order;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    private Double unitPricePen;
    private Double subtotalPen;

    /** Picking: el admin marca que verificó/compró la mercadería de este ítem al llegar el lote. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean picked = false;

    /**
     * Costo USD del producto al momento del pedido (misma base que definio el precio).
     * Congela el costo igual que unitPricePen congela el ingreso: la ganancia del
     * consolidado deja de derivar cuando cambian ofertas/tipo de cambio despues.
     */
    @Column(name = "unit_cost_usd_snapshot")
    private Double unitCostUsdSnapshot;

    /** Peso (g) del producto al momento del pedido (el courier se costea por peso). */
    @Column(name = "weight_g_snapshot")
    private Integer weightGSnapshot;

    public OrderItem() {}

    public void calculateSubtotal() {
        if (unitPricePen != null && quantity != null) {
            subtotalPen = unitPricePen * quantity;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Double getUnitPricePen() { return unitPricePen; }
    public void setUnitPricePen(Double unitPricePen) { this.unitPricePen = unitPricePen; }

    public Double getSubtotalPen() { return subtotalPen; }
    public void setSubtotalPen(Double subtotalPen) { this.subtotalPen = subtotalPen; }

    public boolean isPicked() { return picked; }
    public void setPicked(boolean picked) { this.picked = picked; }

    public Double getUnitCostUsdSnapshot() { return unitCostUsdSnapshot; }
    public void setUnitCostUsdSnapshot(Double unitCostUsdSnapshot) { this.unitCostUsdSnapshot = unitCostUsdSnapshot; }

    public Integer getWeightGSnapshot() { return weightGSnapshot; }
    public void setWeightGSnapshot(Integer weightGSnapshot) { this.weightGSnapshot = weightGSnapshot; }
}
