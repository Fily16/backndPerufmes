package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Posible NSO para un producto, a revisar por la admin ("Es este" / "Ninguno").
 *
 * Tabla propia (NO match_candidates): asi aceptar un candidato NSO no dispara el merge de
 * MatchReviewController.accept, "Vaciar cola" no la borra y no altera los conteos PENDING existentes.
 * Un par (producto, codigo) existe una sola vez: re-evaluar actualiza la fila en vez de duplicarla.
 */
@Entity
@Table(name = "nso_candidates",
        uniqueConstraints = @UniqueConstraint(name = "uk_nso_candidate_product_code",
                columnNames = {"product_id", "nso_code"}),
        indexes = {
                @Index(name = "idx_nso_candidates_status", columnList = "status"),
                @Index(name = "idx_nso_candidates_product_status", columnList = "product_id, status"),
                @Index(name = "idx_nso_candidates_code", columnList = "nso_code")
        })
public class NsoCandidate {

    // --- origin ---
    public static final String ORIGIN_MATCHER = "MATCHER";
    /** Sugerido por la hoja "Con NSO" de la investigacion: NUNCA asigna automatico. */
    public static final String ORIGIN_RESEARCH = "RESEARCH";

    // --- status ---
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    /** Ya no aplica (el producto se resolvio por otro camino o el matcher dejo de proponerlo). */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "nso_candidates_gen")
    @SequenceGenerator(name = "nso_candidates_gen", sequenceName = "nso_candidates_seq", allocationSize = 50)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "nso_code", nullable = false, length = 20)
    private String nsoCode;

    private Double score;

    /** Posicion 1..n dentro de los candidatos del producto (columna candidate_rank: RANK es palabra reservada en algunas BD). */
    @Column(name = "candidate_rank")
    private Integer rank;

    /** Motivos en espanol, JSON array de strings. */
    @Column(name = "reasons_json", length = 4000)
    private String reasonsJson;

    /** MATCHER | RESEARCH */
    @Column(nullable = false, length = 12)
    private String origin = ORIGIN_MATCHER;

    /** PENDING | ACCEPTED | REJECTED | SUPERSEDED */
    @Column(nullable = false, length = 12)
    private String status = STATUS_PENDING;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    public NsoCandidate() {}

    public NsoCandidate(Long productId, String nsoCode, Double score, Integer rank, String origin) {
        this.productId = productId;
        this.nsoCode = nsoCode;
        this.score = score;
        this.rank = rank;
        this.origin = origin;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = STATUS_PENDING;
        if (origin == null) origin = ORIGIN_MATCHER;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getNsoCode() { return nsoCode; }
    public void setNsoCode(String nsoCode) { this.nsoCode = nsoCode; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public String getReasonsJson() { return reasonsJson; }
    public void setReasonsJson(String reasonsJson) { this.reasonsJson = reasonsJson; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
}
