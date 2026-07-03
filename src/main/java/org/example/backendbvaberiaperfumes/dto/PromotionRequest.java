package org.example.backendbvaberiaperfumes.dto;

import java.util.List;

/** Cuerpo para crear/editar una promoción (pack) desde el admin. */
public class PromotionRequest {
    private String name;
    private String imageUrl;
    private String imageData;      // imagen subida (data URL base64)
    private Double pricePen;
    private Double profitPen;      // opcional: si null, se calcula (solo si todos los ítems son del catálogo)
    private Integer stockQty;
    private String validUntil;     // fecha ISO "yyyy-MM-dd", opcional
    private Boolean active;
    private List<ItemReq> items;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getImageData() { return imageData; }
    public void setImageData(String imageData) { this.imageData = imageData; }

    public Double getPricePen() { return pricePen; }
    public void setPricePen(Double pricePen) { this.pricePen = pricePen; }

    public Double getProfitPen() { return profitPen; }
    public void setProfitPen(Double profitPen) { this.profitPen = profitPen; }

    public Integer getStockQty() { return stockQty; }
    public void setStockQty(Integer stockQty) { this.stockQty = stockQty; }

    public String getValidUntil() { return validUntil; }
    public void setValidUntil(String validUntil) { this.validUntil = validUntil; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public List<ItemReq> getItems() { return items; }
    public void setItems(List<ItemReq> items) { this.items = items; }

    public static class ItemReq {
        private Long productId;   // null => perfume exclusivo de la promo
        private String name;
        private String imageUrl;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    }
}
