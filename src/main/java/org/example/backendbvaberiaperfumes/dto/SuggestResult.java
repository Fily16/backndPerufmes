package org.example.backendbvaberiaperfumes.dto;

import org.example.backendbvaberiaperfumes.model.Product;

/** Resultado ligero para el dropdown del buscador (miniatura + precio). */
public class SuggestResult {
    private Long id;
    private String brand;
    private String name;
    private Integer ml;
    private String imageUrl;
    private Double wholesalePricePen;
    private Double retailPricePen;

    public SuggestResult() {}

    public static SuggestResult from(Product p) {
        SuggestResult s = new SuggestResult();
        s.id = p.getId();
        s.brand = p.getBrand();
        s.name = p.getName();
        s.ml = p.getMl();
        s.imageUrl = p.getImageUrl();
        s.wholesalePricePen = p.getWholesalePricePen();
        s.retailPricePen = p.getRetailPricePen();
        return s;
    }

    public Long getId() { return id; }
    public String getBrand() { return brand; }
    public String getName() { return name; }
    public Integer getMl() { return ml; }
    public String getImageUrl() { return imageUrl; }
    public Double getWholesalePricePen() { return wholesalePricePen; }
    public Double getRetailPricePen() { return retailPricePen; }
}
