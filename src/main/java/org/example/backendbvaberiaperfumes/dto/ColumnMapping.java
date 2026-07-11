package org.example.backendbvaberiaperfumes.dto;

/**
 * Asignacion de columnas de un Excel de proveedor. La auto-deteccion la propone
 * y el admin puede corregirla desde la vista previa (indices 0-based de columna).
 */
public class ColumnMapping {
    public Integer headerRow;       // fila del header (0-based)
    public Integer descriptionCol;  // requerida
    public Integer upcCol;          // opcional (sin ella -> fila sin codigo)
    public Integer priceCol;        // costo del proveedor
    public Integer brandCol;        // opcional
    public Integer statusCol;       // opcional (disponibilidad)
    public String sizeUnit = "auto"; // auto | ml | oz

    public ColumnMapping() {}

    public boolean hasCore() {
        return descriptionCol != null && priceCol != null;
    }
}
