package org.example.backendbvaberiaperfumes.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public class OrderRequest {
    @NotBlank
    private String clientName;

    @NotBlank
    private String clientPhone;

    /** Ítems de producto. Puede venir vacío si el pedido es solo de promociones. */
    private List<OrderItemRequest> items;

    private String existingOrderCode;
    private String yapeReference;

    /** Canal elegido por el cliente: CONSOLIDADO (por encargo) o STOCK (entrega inmediata). */
    private String channel;

    // --- CAMPOS DE ENVÍO DESDE EL FRONTEND ---
    private String deliveryMethod;
    private String shippingName;
    private String shippingDni;
    private String shippingPhone;
    private String shippingAddress;
    private String shippingDepartment; // Departamento destino (Shalom)
    private String shippingAgency;     // Sede/agencia Shalom

    /** Promociones (packs) compradas en este pedido. */
    private List<PromoLineRequest> promotions;

    // Getters y Setters
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getClientPhone() { return clientPhone; }
    public void setClientPhone(String clientPhone) { this.clientPhone = clientPhone; }

    public List<OrderItemRequest> getItems() { return items; }
    public void setItems(List<OrderItemRequest> items) { this.items = items; }

    public String getExistingOrderCode() { return existingOrderCode; }
    public void setExistingOrderCode(String existingOrderCode) { this.existingOrderCode = existingOrderCode; }

    public String getYapeReference() { return yapeReference; }
    public void setYapeReference(String yapeReference) { this.yapeReference = yapeReference; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

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

    public List<PromoLineRequest> getPromotions() { return promotions; }
    public void setPromotions(List<PromoLineRequest> promotions) { this.promotions = promotions; }

    public static class PromoLineRequest {
        private Long promotionId;
        private Integer quantity;

        public Long getPromotionId() { return promotionId; }
        public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }

    public static class OrderItemRequest {
        private Long productId;
        private Integer quantity;
        private Double unitPricePen;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public Double getUnitPricePen() { return unitPricePen; }
        public void setUnitPricePen(Double unitPricePen) { this.unitPricePen = unitPricePen; }
    }
}