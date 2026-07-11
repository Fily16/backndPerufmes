package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Importacion en staging: se guarda el Excel crudo (base64) para poder re-parsear con
 * columnas corregidas y previsualizar, SIN tocar el catalogo hasta que el admin publica.
 */
@Entity
@Table(name = "import_batches")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    private String filename;

    /** Excel original en base64 (TEXT: portable H2/Postgres, evita LOB/OID). */
    @Column(name = "file_base64", columnDefinition = "TEXT")
    private String fileBase64;

    /** PENDING | PUBLISHED | DISCARDED */
    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "column_map_json", length = 2000)
    private String columnMapJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public ImportBatch() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getFileBase64() { return fileBase64; }
    public void setFileBase64(String fileBase64) { this.fileBase64 = fileBase64; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getColumnMapJson() { return columnMapJson; }
    public void setColumnMapJson(String columnMapJson) { this.columnMapJson = columnMapJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
