package org.example.backendbvaberiaperfumes.dto;

/** Fila ya parseada y normalizada de un Excel de proveedor. */
public class ParsedRow {
    public String gtin;        // GTIN-14 validado (checksum OK) o null
    public String gtinRaw;     // digitos crudos del codigo tal como vinieron (auditoria/cuarentena)
    public String gtinStatus;  // OK | EMPTY | INVALID_LENGTH | CHECKSUM_FAIL | AMBIGUOUS
    public String brand;
    public String name;        // nombre legible (sin tamano/genero)
    public String rawTitle;    // titulo original del Excel
    public Integer ml;
    public String forma;       // single | set | oil | deo
    public Double costUsd;
    public boolean inStock = true;
    public boolean flashSale = false;
    public String supplierSku;
    public String imageUrl;    // foto elegida a mano / traida de Apify (solo para productos nuevos)

    public ParsedRow() {}

    public boolean hasGtin() { return gtin != null && !gtin.isBlank(); }
}
