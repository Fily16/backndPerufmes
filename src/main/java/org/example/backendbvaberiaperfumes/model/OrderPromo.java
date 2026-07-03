package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * Línea de promoción dentro de un pedido. Guarda un snapshot del precio y ganancia
 * al momento de la compra, para que el pedido sobreviva a ediciones/borrado de la promo.
 */
@Entity
@Table(name = "order_promos")
public class OrderPromo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private Order order;

    /** Id de la promo (null si la promo fue borrada luego). */
    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "promo_name")
    private String promoName;

    @Column(name = "unit_price_pen")
    private Double unitPricePen;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "subtotal_pen")
    private Double subtotalPen;

    /** Ganancia unitaria del pack (snapshot). Ganancia total = profitPen * quantity. */
    @Column(name = "profit_pen")
    private Double profitPen;

    public OrderPromo() {}

    public void calculateSubtotal() {
        if (unitPricePen != null && quantity != null) {
            subtotalPen = unitPricePen * quantity;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

    public String getPromoName() { return promoName; }
    public void setPromoName(String promoName) { this.promoName = promoName; }

    public Double getUnitPricePen() { return unitPricePen; }
    public void setUnitPricePen(Double unitPricePen) { this.unitPricePen = unitPricePen; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Double getSubtotalPen() { return subtotalPen; }
    public void setSubtotalPen(Double subtotalPen) { this.subtotalPen = subtotalPen; }

    public Double getProfitPen() { return profitPen; }
    public void setProfitPen(Double profitPen) { this.profitPen = profitPen; }
}
