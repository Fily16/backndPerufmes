package org.example.backendbvaberiaperfumes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class OrderRequest {
    @NotBlank
    private String clientName;

    @NotBlank
    private String clientPhone;

    @NotEmpty
    private List<OrderItemRequest> items;

    private String existingOrderCode;
    private String yapeReference;

    // --- CAMPOS DE ENVÍO DESDE EL FRONTEND ---
    private String deliveryMethod;
    private String shippingName;
    private String shippingDni;
    private String shippingPhone;
    private String shippingAddress;

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