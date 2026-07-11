package org.example.backendbvaberiaperfumes.dto;

import java.util.Map;

/**
 * Correcciones manuales del admin al publicar: por indice de fila (el mismo del preview),
 * marca/nombre/ml elegidos a mano para productos NUEVOS (o sin UPC). Los productos que ya
 * existen por UPC no se tocan (conservan sus datos).
 */
public class PublishRequest {
    public Map<String, RowOverride> overrides;

    public static class RowOverride {
        public String brand;
        public String name;
        public Integer ml;
        public String imageUrl;   // foto traida de Apify (o pegada a mano) para el producto nuevo
    }
}
