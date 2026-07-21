package org.example.backendbvaberiaperfumes.dto;

import java.util.List;

/**
 * Perfume pedido por clientes pero que NO está disponible en ningún proveedor (tras importar
 * los Excel). Incluye a qué pedidos/clientes corresponde, para avisarles por WhatsApp.
 */
public class MissingItem {
    private Long productId;
    private String brand;
    private String name;
    private Integer ml;
    private Double priceUsd;
    /** Precio registrado en el sistema (mayorista PEN) — "el sistema ya tiene calculado su precio". */
    private Double registeredPricePen;
    /** CRIST_PENDING (Caso A por defecto) | CRIST_BOUGHT | UNAVAILABLE (Caso B). */
    private String resolutionStatus;
    private List<OrderRef> orders;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getMl() { return ml; }
    public void setMl(Integer ml) { this.ml = ml; }

    public Double getPriceUsd() { return priceUsd; }
    public void setPriceUsd(Double priceUsd) { this.priceUsd = priceUsd; }

    public Double getRegisteredPricePen() { return registeredPricePen; }
    public void setRegisteredPricePen(Double registeredPricePen) { this.registeredPricePen = registeredPricePen; }

    public String getResolutionStatus() { return resolutionStatus; }
    public void setResolutionStatus(String resolutionStatus) { this.resolutionStatus = resolutionStatus; }

    public List<OrderRef> getOrders() { return orders; }
    public void setOrders(List<OrderRef> orders) { this.orders = orders; }

    /** Referencia al pedido/cliente que pidió el perfume faltante. */
    public static class OrderRef {
        private String orderCode;
        private String clientName;
        private String clientPhone;
        private Integer quantity;

        public String getOrderCode() { return orderCode; }
        public void setOrderCode(String orderCode) { this.orderCode = orderCode; }

        public String getClientName() { return clientName; }
        public void setClientName(String clientName) { this.clientName = clientName; }

        public String getClientPhone() { return clientPhone; }
        public void setClientPhone(String clientPhone) { this.clientPhone = clientPhone; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
