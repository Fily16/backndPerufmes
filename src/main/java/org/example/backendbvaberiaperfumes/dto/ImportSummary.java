package org.example.backendbvaberiaperfumes.dto;

import java.util.ArrayList;
import java.util.List;

/** Resultado de importar un Excel de proveedor. Se devuelve al admin. */
public class ImportSummary {
    private String supplierName;
    private int rowsRead;
    private int productsCreated;
    private int offersCreated;
    private int offersUpdated;
    private int trueDuplicates;     // mismo UPC y mismo producto -> colapsados
    private int collisions;         // mismo UPC pero producto distinto -> separados
    private int noUpcRows;          // filas sin codigo de barras
    private int markedOutOfStock;   // ofertas que ya no vinieron en este Excel
    private int l2AutoMatched;      // filas sin UPC enganchadas a producto existente por nombre
    private int reviewQueued;       // candidatos enviados a la cola de revision del admin
    private int suspiciousRows;     // filas con costo fuera de rango, guardadas fuera de stock
    private int gtinAdopted;        // productos sin UPC que adoptaron el codigo entrante (unificados)
    private final List<String> notes = new ArrayList<>();

    public void addNote(String n) { notes.add(n); }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String s) { this.supplierName = s; }
    public int getRowsRead() { return rowsRead; }
    public void setRowsRead(int v) { this.rowsRead = v; }
    public int getProductsCreated() { return productsCreated; }
    public void setProductsCreated(int v) { this.productsCreated = v; }
    public int getOffersCreated() { return offersCreated; }
    public void setOffersCreated(int v) { this.offersCreated = v; }
    public int getOffersUpdated() { return offersUpdated; }
    public void setOffersUpdated(int v) { this.offersUpdated = v; }
    public int getTrueDuplicates() { return trueDuplicates; }
    public void setTrueDuplicates(int v) { this.trueDuplicates = v; }
    public int getCollisions() { return collisions; }
    public void setCollisions(int v) { this.collisions = v; }
    public int getNoUpcRows() { return noUpcRows; }
    public void setNoUpcRows(int v) { this.noUpcRows = v; }
    public int getMarkedOutOfStock() { return markedOutOfStock; }
    public void setMarkedOutOfStock(int v) { this.markedOutOfStock = v; }
    public int getL2AutoMatched() { return l2AutoMatched; }
    public void setL2AutoMatched(int v) { this.l2AutoMatched = v; }
    public int getReviewQueued() { return reviewQueued; }
    public void setReviewQueued(int v) { this.reviewQueued = v; }
    public int getSuspiciousRows() { return suspiciousRows; }
    public void setSuspiciousRows(int v) { this.suspiciousRows = v; }
    public int getGtinAdopted() { return gtinAdopted; }
    public void setGtinAdopted(int v) { this.gtinAdopted = v; }
    public List<String> getNotes() { return notes; }
}
