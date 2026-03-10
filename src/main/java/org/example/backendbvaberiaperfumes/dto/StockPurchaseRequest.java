package org.example.backendbvaberiaperfumes.dto;

import java.util.List;

public class StockPurchaseRequest {
    private List<StockItem> items;

    public List<StockItem> getItems() { return items; }
    public void setItems(List<StockItem> items) { this.items = items; }

    public static class StockItem {
        private Long productId;
        private Integer quantity;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
