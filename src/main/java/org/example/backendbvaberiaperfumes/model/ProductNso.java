package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Estado NSO de un producto (1 fila por producto; sin fila == SIN_VERIFICAR).
 *
 * Tabla aparte a proposito: los repricings hacen productRepo.save(p) con entidades cargadas antes y
 * pisarian columnas NSO si vivieran en Product; ademas el JSON publico de Product queda identico.
 * Implementa Persistable (id asignado = productId) para insertar sin SELECT previo en rematch masivos.
 */
@Entity
@Table(name = "product_nso", indexes = {
        @Index(name = "idx_product_nso_status", columnList = "status"),
        @Index(name = "idx_product_nso_code", columnList = "nso_code"),
        @Index(name = "idx_product_nso_brand_key", columnList = "brand_key")
})
public class ProductNso implements Persistable<Long> {

    // --- status ---
    public static final String STATUS_CON_NSO = "CON_NSO";
    public static final String STATUS_EN_REVISION = "EN_REVISION";
    public static final String STATUS_MARCA_CON_NSO = "MARCA_CON_NSO";
    public static final String STATUS_SIN_NSO = "SIN_NSO";
    /** Implicito: un producto sin fila en product_nso esta SIN_VERIFICAR. */
    public static final String STATUS_SIN_VERIFICAR = "SIN_VERIFICAR";
    public static final List<String> STATUSES = List.of(
            STATUS_CON_NSO, STATUS_EN_REVISION, STATUS_MARCA_CON_NSO, STATUS_SIN_NSO, STATUS_SIN_VERIFICAR);

    // --- matchedBy ---
    public static final String MATCHED_UPC = "UPC";
    public static final String MATCHED_ALIAS_SKU = "ALIAS_SKU";
    public static final String MATCHED_ALIAS_NOMBRE = "ALIAS_NOMBRE";
    public static final String MATCHED_NOMBRE = "NOMBRE";
    public static final String MATCHED_MANUAL = "MANUAL";
    public static final String MATCHED_APROBADO = "APROBADO";

    @Id
    @Column(name = "product_id")
    private Long productId;

    /** CON_NSO | EN_REVISION | MARCA_CON_NSO | SIN_NSO | SIN_VERIFICAR */
    @Column(nullable = false, length = 20)
    private String status = STATUS_SIN_VERIFICAR;

    /** Codigo asignado (solo significativo con status CON_NSO). */
    @Column(name = "nso_code", length = 20)
    private String nsoCode;

    /** UPC | ALIAS_SKU | ALIAS_NOMBRE | NOMBRE | MANUAL | APROBADO (null si no es CON_NSO). */
    @Column(name = "matched_by", length = 20)
    private String matchedBy;

    /** Similitud mostrada a la admin (solo ordena; nunca decide automatico). */
    private Double score;

    /** Motivos en espanol, JSON array de strings. */
    @Column(name = "reasons_json", length = 4000)
    private String reasonsJson;

    /** Marca normalizada con la que se resolvio (NsoKeys.brandKey); null si la marca no se reconocio. */
    @Column(name = "brand_key", length = 150)
    private String brandKey;

    /** Decision del admin (aprobar/asignar/quitar): el matcher la respeta mientras el codigo siga activo. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean locked = false;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Ultima vez que el matcher evaluo este producto. */
    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** nso_catalog_version con la que se evaluo (para saber si quedo desactualizado). */
    @Column(name = "catalog_version")
    private Integer catalogVersion;

    @Transient
    @JsonIgnore
    private boolean newEntity = true;

    public ProductNso() {}

    public ProductNso(Long productId, String status) {
        this.productId = productId;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        if (status == null) status = STATUS_SIN_VERIFICAR;
        if (locked == null) locked = false;
        if (checkedAt == null) checkedAt = LocalDateTime.now();
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        this.newEntity = false;
    }

    // --- Persistable ---

    @Override
    @JsonIgnore
    public Long getId() { return productId; }

    @Override
    @JsonIgnore
    public boolean isNew() { return newEntity; }

    @JsonIgnore
    public boolean isConNso() { return STATUS_CON_NSO.equals(status); }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNsoCode() { return nsoCode; }
    public void setNsoCode(String nsoCode) { this.nsoCode = nsoCode; }
    public String getMatchedBy() { return matchedBy; }
    public void setMatchedBy(String matchedBy) { this.matchedBy = matchedBy; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getReasonsJson() { return reasonsJson; }
    public void setReasonsJson(String reasonsJson) { this.reasonsJson = reasonsJson; }
    public String getBrandKey() { return brandKey; }
    public void setBrandKey(String brandKey) { this.brandKey = brandKey; }
    public Boolean getLocked() { return locked; }
    public void setLocked(Boolean locked) { this.locked = locked; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
    public Integer getCatalogVersion() { return catalogVersion; }
    public void setCatalogVersion(Integer catalogVersion) { this.catalogVersion = catalogVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(productId, ((ProductNso) o).productId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(productId);
    }
}
