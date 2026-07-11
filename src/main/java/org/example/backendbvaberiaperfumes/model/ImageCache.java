package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Caché de imágenes ya buscadas en Apify, por UPC (o por texto de consulta si no hay UPC).
 * Evita re-llamar a Apify: una vez que un UPC tiene foto, jamás se vuelve a pedir
 * (aunque cambies de sesión, refresques o llegue otro proveedor con ese mismo UPC).
 */
@Entity
@Table(name = "image_cache")
public class ImageCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Clave estable: el UPC/GTIN si existe; si no, "Q:" + consulta en minúsculas. */
    @Column(name = "cache_key", unique = true, nullable = false, length = 400)
    private String cacheKey;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt = LocalDateTime.now();

    public ImageCache() {}

    public ImageCache(String cacheKey, String imageUrl) {
        this.cacheKey = cacheKey;
        this.imageUrl = imageUrl;
        this.fetchedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCacheKey() { return cacheKey; }
    public void setCacheKey(String cacheKey) { this.cacheKey = cacheKey; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
}
