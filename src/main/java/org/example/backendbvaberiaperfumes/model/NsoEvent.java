package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Bitacora append-only del sistema NSO: cambios de estado, decisiones de la admin, cargas del catalogo
 * y encendido/apagado del filtro. Nunca se edita ni se borra (auditoria).
 */
@Entity
@Table(name = "nso_events", indexes = {
        @Index(name = "idx_nso_events_product", columnList = "product_id"),
        @Index(name = "idx_nso_events_type", columnList = "type"),
        @Index(name = "idx_nso_events_created", columnList = "created_at")
})
public class NsoEvent {

    // --- type ---
    /** El matcher cambio el estado de un producto. */
    public static final String TYPE_STATUS_CHANGE = "STATUS_CHANGE";
    public static final String TYPE_CANDIDATE_ACCEPTED = "CANDIDATE_ACCEPTED";
    public static final String TYPE_CANDIDATES_REJECTED = "CANDIDATES_REJECTED";
    public static final String TYPE_MANUAL_ASSIGN = "MANUAL_ASSIGN";
    public static final String TYPE_UNASSIGN = "UNASSIGN";
    public static final String TYPE_CATALOG_UPLOAD = "CATALOG_UPLOAD";
    public static final String TYPE_CODE_CREATED = "CODE_CREATED";
    public static final String TYPE_CODE_ACTIVATED = "CODE_ACTIVATED";
    public static final String TYPE_CODE_DEACTIVATED = "CODE_DEACTIVATED";
    public static final String TYPE_BRAND_ALIAS = "BRAND_ALIAS";
    public static final String TYPE_GATE_ON = "GATE_ON";
    public static final String TYPE_GATE_OFF = "GATE_OFF";
    /** Cambio de opciones del panel (aceptar NSO de otros paises CAN, umbral de revision). */
    public static final String TYPE_SETTINGS = "SETTINGS";
    public static final String TYPE_REMATCH = "REMATCH";
    public static final String TYPE_PRODUCT_MERGED = "PRODUCT_MERGED";
    public static final String TYPE_PRODUCT_DELETED = "PRODUCT_DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "nso_events_gen")
    @SequenceGenerator(name = "nso_events_gen", sequenceName = "nso_events_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "nso_code", length = 20)
    private String nsoCode;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", length = 20)
    private String toStatus;

    /** Email del admin o "sistema". */
    @Column(length = 150)
    private String actor;

    /** Detalle legible en espanol (o JSON corto, ej. resumen de una carga). */
    @Column(length = 2000)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public NsoEvent() {}

    public NsoEvent(String type, Long productId, String nsoCode, String fromStatus, String toStatus,
                    String actor, String detail) {
        this.type = type;
        this.productId = productId;
        this.nsoCode = nsoCode;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        setDetail(detail);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getNsoCode() { return nsoCode; }
    public void setNsoCode(String nsoCode) { this.nsoCode = nsoCode; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getDetail() { return detail; }
    /** Recorta a 2000 caracteres para no romper el insert. */
    public void setDetail(String detail) {
        this.detail = detail != null && detail.length() > 2000 ? detail.substring(0, 2000) : detail;
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
