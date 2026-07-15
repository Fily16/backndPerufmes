package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Imagen de la galeria (banners/flyers de consolidados), guardada EN la BD:
 * el hosting (Render) no tiene disco persistente. Precedente: Promotion.imageData.
 * Se sirve por GET /api/media/{id} con cache inmutable (no se edita: se reemplaza).
 */
@Entity
@Table(name = "media_images")
public class MediaImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 160)
    private String name;

    /** image/webp | image/jpeg | image/png */
    @Column(name = "content_type", length = 60)
    private String contentType;

    /** Base64 PURO (sin prefijo data:). Comprimida en el navegador antes de subir (~150KB). */
    @JsonIgnore
    @Column(name = "data_base64", columnDefinition = "text")
    private String dataBase64;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getDataBase64() { return dataBase64; }
    public void setDataBase64(String dataBase64) { this.dataBase64 = dataBase64; }
    public Integer getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Integer sizeBytes) { this.sizeBytes = sizeBytes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
