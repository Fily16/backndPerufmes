package org.example.backendbvaberiaperfumes.dto;

public class ProductCount {
    private Long productId;
    private String brand;
    private String name;
    private int ml;
    private int quantity;

    public ProductCount(Long productId, String brand, String name, int ml, int quantity) {
        this.productId = productId;
        this.brand = brand;
        this.name = name;
        this.ml = ml;
        this.quantity = quantity;
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getMl() { return ml; }
    public void setMl(int ml) { this.ml = ml; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
