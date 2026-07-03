package org.example.backendbvaberiaperfumes.dto;

/** Fila ya parseada y normalizada de un Excel de proveedor. */
public class ParsedRow {
    public String gtin;        // GTIN-14 o null si no hay codigo valido
    public String brand;
    public String name;        // nombre legible (sin tamano/genero)
    public String rawTitle;    // titulo original del Excel
    public Integer ml;
    public String forma;       // single | set | oil | deo
    public Double costUsd;
    public boolean inStock = true;
    public boolean flashSale = false;
    public String supplierSku;

    public ParsedRow() {}

    public boolean hasGtin() { return gtin != null && !gtin.isBlank(); }
}
