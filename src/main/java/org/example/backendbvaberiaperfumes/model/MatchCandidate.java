package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Candidato a fusion/revision detectado por el motor de matching.
 * NUNCA se fusiona solo: el admin lo acepta o rechaza desde la cola de revision.
 *
 * kind:
 *  - L2_IMPORT:     una fila importada sin GTIN confiable se parece a un producto existente.
 *  - DEDUP_SCAN:    escaneo del catalogo completo encontro dos productos que parecen el mismo.
 *  - GTIN_TYPO:     un codigo con checksum invalido esta a 1 digito de un GTIN valido del catalogo.
 *  - ATTR_CONFLICT: mismo GTIN pero atributos contradictorios entre proveedores (ej. 200ml vs 100ml).
 */
@Entity
@Table(name = "match_candidates")
public class MatchCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String kind;

    /** Producto "duplicado" (normalmente el recien creado o el que se fusionaria en otro). */
    @Column(name = "source_product_id")
    private Long sourceProductId;

    /** Producto canonico del catalogo contra el que se propone la fusion. */
    @Column(name = "target_product_id")
    private Long targetProductId;

    /** Oferta que origino la deteccion (si aplica). */
    @Column(name = "supplier_offer_id")
    private Long supplierOfferId;

    private Double score;

    @Column(name = "reasons_json", length = 2000)
    private String reasonsJson;

    /** PENDING | ACCEPTED | REJECTED */
    @Column(nullable = false, length = 12)
    private String status = "PENDING";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getSourceProductId() { return sourceProductId; }
    public void setSourceProductId(Long sourceProductId) { this.sourceProductId = sourceProductId; }
    public Long getTargetProductId() { return targetProductId; }
    public void setTargetProductId(Long targetProductId) { this.targetProductId = targetProductId; }
    public Long getSupplierOfferId() { return supplierOfferId; }
    public void setSupplierOfferId(Long supplierOfferId) { this.supplierOfferId = supplierOfferId; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getReasonsJson() { return reasonsJson; }
    public void setReasonsJson(String reasonsJson) { this.reasonsJson = reasonsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
}
