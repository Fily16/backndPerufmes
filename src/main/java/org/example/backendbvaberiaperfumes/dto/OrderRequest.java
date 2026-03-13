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

    // --- NUEVOS CAMPOS AÑADIDOS PARA ACUMULAR PEDIDOS ---
    private String existingOrderCode;
    private String yapeReference;

    // Getters y Setters originales
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getClientPhone() { return clientPhone; }
    public void setClientPhone(String clientPhone) { this.clientPhone = clientPhone; }

    public List<OrderItemRequest> getItems() { return items; }
    public void setItems(List<OrderItemRequest> items) { this.items = items; }

    // --- Getters y Setters de los nuevos campos ---
    public String getExistingOrderCode() { return existingOrderCode; }
    public void setExistingOrderCode(String existingOrderCode) { this.existingOrderCode = existingOrderCode; }

    public String getYapeReference() { return yapeReference; }
    public void setYapeReference(String yapeReference) { this.yapeReference = yapeReference; }

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