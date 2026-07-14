package org.example.backendbvaberiaperfumes.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Vista previa de una importacion ANTES de publicarla al cliente. No escribe catalogo.
 * Campos publicos -> JSON directo (estilo AllocationResponse).
 */
public class ImportPreview {
    public Long batchId;
    public String supplierName;
    public boolean generic;             // true = parser generico (mostrar selector de columnas en la UI)
    public ColumnMapping mapping;       // columnas detectadas/usadas
    public List<String> headers = new ArrayList<>(); // nombres de columnas del Excel (para los desplegables)

    public int rowsRead;
    public int newProducts;
    public int updatedOffers;
    public int noUpcRows;
    public int collisions;
    public int outOfStock;
    public int priceDrops;
    public int priceRises;
    public int l2AutoMatched;      // filas sin UPC enganchadas a un producto existente por nombre (seguro)
    public int reviewCandidates;   // filas que iran a la cola de revision al publicar
    public int suspiciousRows;     // filas con costo fuera del rango plausible (typo probable)
    public boolean layoutFallback; // el parser afinado no reconocio el layout; se uso el generico

    public List<Line> rows = new ArrayList<>();

    public static class Line {
        public int idx;                 // indice de la fila en el parseo (para overrides al publicar)
        public String brand;            // marca parseada (editable si es nuevo)
        public String name;             // nombre parseado sin la marca (editable si es nuevo)
        public String rawTitle;         // titulo original del Excel (para buscar en Apify por nombre exacto)
        public Integer ml;              // ml parseado (editable si es nuevo)
        public String upc;
        public Double costUsd;
        public boolean inStock;
        public boolean isNew;           // no existe aun como producto (por UPC)
        public boolean editable;        // el admin puede editar marca/nombre/ml (solo filas nuevas)

        public Long matchedProductId;
        // Datos que SE CONSERVAN del producto existente (cruce por UPC): se muestran, no se pisan.
        public String matchedBrand;
        public String matchedName;
        public Integer matchedMl;
        public String matchedImageUrl;

        public Double currentPricePen;  // precio publico actual (si existe)
        public Double newPricePen;      // precio publico que quedaria tras publicar

        public String matchLevel;       // L1 | L2_AUTO | L2_REVIEW | NEW | EXISTING
        public Double matchScore;       // similitud del mejor candidato (si aplica)
        public String gtinStatus;       // OK | EMPTY | INVALID_LENGTH | CHECKSUM_FAIL | AMBIGUOUS
        public boolean suspicious;      // costo fuera del rango plausible: no repreciara salvo aprobacion
    }
}
