package org.example.backendbvaberiaperfumes.dto;

import java.util.List;

/** Pedido de fotos: una lista de filas con su idx, UPC (para el caché) y consulta (para Apify). */
public class ImageSearchRequest {
    public List<Item> items;
    public String source;   // "google" (por defecto) | "fragrantica" | "bing"
    public boolean force;   // true = ignora el caché y re-busca (para reemplazar fotos rotas)

    public static class Item {
        public int idx;
        public String upc;    // GTIN-14 si la fila lo tiene; null si no
        public String query;  // "marca nombre 100ml perfume"
    }
}
