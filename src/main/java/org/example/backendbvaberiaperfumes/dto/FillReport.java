package org.example.backendbvaberiaperfumes.dto;

import java.util.ArrayList;
import java.util.List;

/** Resumen de "Completar Excel del proveedor" (llenado de cantidades por UPC). */
public class FillReport {
    public String supplierName;
    public String sheetName;
    public int totalRows;                          // filas de datos recorridas
    public int found;                              // líneas del pedido ubicadas en el Excel
    public int updated;                            // celdas de cantidad escritas
    public int matchedByCode;                      // ubicadas por UPC / dígitos crudos
    public int matchedByName;                      // ubicadas por SKU o nombre (fallback robusto)
    public int hiddenRows;                         // filas ocultadas (no pedidas)
    public List<Missing> notFound = new ArrayList<>();     // pedido pero ausente del Excel (por ninguna clave)
    public long durationMs;

    public static class Missing {
        public String gtin;
        public String brand;
        public String name;
        public int quantity;
        public Missing() { }
        public Missing(String gtin, String brand, String name, int quantity) {
            this.gtin = gtin; this.brand = brand; this.name = name; this.quantity = quantity;
        }
    }
}
