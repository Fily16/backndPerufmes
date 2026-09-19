package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Memoria de decisiones NSO: "esta clave (GTIN / SKU de proveedor / nombre normalizado / marca) SI o NO
 * corresponde a este codigo". Una aprobacion cubre los otros tamanos del mismo perfume (NAME_KEY no lleva ml)
 * y un rechazo (NEGATIVE) evita que el matcher lo vuelva a proponer.
 *
 * kind / aliasKey (claves armadas SIEMPRE con NsoKeys):
 *  - GTIN:         GTIN-14 validado.                                   nsoCode obligatorio.
 *  - SUPPLIER_SKU: "proveedorNormalizado|SKU" (ej. "oasis|PERF-AFNA-26"). nsoCode obligatorio.
 *  - NAME_KEY:     "brandKey|core ordenado|concentracion|genero|forma".   nsoCode obligatorio.
 *  - BRAND:        marca del proveedor normalizada -> targetBrandKey (marca del catalogo). nsoCode null.
 *
 * origin RESEARCH = links de la hoja "Con NSO" de la investigacion: tiene falsos positivos probados,
 * por eso esos alias NUNCA asignan automatico; solo son evidencia para proponer candidatos a revision.
 *
 * Unicidad (kind, alias_key, code_key): code_key es nsoCode o "*" cuando nsoCode es null (BRAND). Se usa
 * una columna no nula porque en Postgres y H2 dos NULL no chocan en un UNIQUE y la marca quedaria duplicable.
 * La polaridad NO es parte de la clave: cambiar de opinion actualiza la fila (POSITIVE <-> NEGATIVE).
 */
@Entity
@Table(name = "nso_aliases",
        uniqueConstraints = @UniqueConstraint(name = "uk_nso_alias_kind_key_code",
                columnNames = {"kind", "alias_key", "code_key"}),
        indexes = {
                @Index(name = "idx_nso_aliases_key", columnList = "alias_key"),
                @Index(name = "idx_nso_aliases_code", columnList = "nso_code"),
                @Index(name = "idx_nso_aliases_origin", columnList = "origin")
        })
public class NsoAlias {

    // --- kind ---
    public static final String KIND_GTIN = "GTIN";
    public static final String KIND_SUPPLIER_SKU = "SUPPLIER_SKU";
    public static final String KIND_NAME_KEY = "NAME_KEY";
    public static final String KIND_BRAND = "BRAND";

    // --- polarity ---
    public static final String POSITIVE = "POSITIVE";
    public static final String NEGATIVE = "NEGATIVE";

    // --- origin ---
    /** La admin acepto un candidato ("Es este"). */
    public static final String ORIGIN_APROBADO = "APROBADO";
    /** La admin rechazo candidatos ("Ninguno"). */
    public static final String ORIGIN_RECHAZADO = "RECHAZADO";
    /** La admin asigno un codigo a mano. */
    public static final String ORIGIN_MANUAL = "MANUAL";
    /** Alias de marca creado por la admin (marca del proveedor -> marca del catalogo). */
    public static final String ORIGIN_ADMIN = "ADMIN";
    /** Link de la hoja "Con NSO" de la investigacion: solo evidencia, NUNCA automatico. */
    public static final String ORIGIN_RESEARCH = "RESEARCH";

    /** Valor de code_key cuando nsoCode es null (alias BRAND). */
    public static final String NO_CODE = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "nso_aliases_gen")
    @SequenceGenerator(name = "nso_aliases_gen", sequenceName = "nso_aliases_seq", allocationSize = 50)
    private Long id;

    /** GTIN | SUPPLIER_SKU | NAME_KEY | BRAND */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "alias_key", nullable = false, length = 400)
    private String aliasKey;

    /** Codigo NSO al que apunta; null solo para BRAND. */
    @Column(name = "nso_code", length = 20)
    private String nsoCode;

    /** nsoCode o "*" (se calcula solo antes de guardar; parte de la clave unica). */
    @Column(name = "code_key", nullable = false, length = 20)
    private String codeKey = NO_CODE;

    /** Solo BRAND: brandKey del catalogo al que equivale la marca del proveedor. */
    @Column(name = "target_brand_key", length = 150)
    private String targetBrandKey;

    /** POSITIVE | NEGATIVE */
    @Column(nullable = false, length = 10)
    private String polarity = POSITIVE;

    /** APROBADO | RECHAZADO | MANUAL | ADMIN | RESEARCH */
    @Column(nullable = false, length = 12)
    private String origin;

    /** Producto cuya decision creo el alias (trazabilidad; null en RESEARCH/ADMIN). */
    @Column(name = "source_product_id")
    private Long sourceProductId;

    /** Texto libre para mostrar (ej. RESEARCH: "ALTA · Afnan Supremacy Gala W EDP 3.0 oz (tester)"). */
    @Column(length = 500)
    private String detail;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public NsoAlias() {}

    public NsoAlias(String kind, String aliasKey, String nsoCode, String polarity, String origin) {
        this.kind = kind;
        this.aliasKey = aliasKey;
        setNsoCode(nsoCode);
        this.polarity = polarity;
        this.origin = origin;
    }

    /** Alias BRAND: marca del proveedor (ya normalizada) -> brandKey del catalogo. */
    public static NsoAlias brand(String supplierBrandKey, String catalogBrandKey, String origin) {
        NsoAlias a = new NsoAlias(KIND_BRAND, supplierBrandKey, null, POSITIVE, origin);
        a.setTargetBrandKey(catalogBrandKey);
        return a;
    }

    /** code_key de un codigo (o "*" si es null). Util para buscar por la clave unica. */
    public static String codeKeyOf(String nsoCode) {
        return nsoCode == null || nsoCode.isBlank() ? NO_CODE : nsoCode;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        syncCodeKey();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        syncCodeKey();
    }

    private void syncCodeKey() {
        codeKey = codeKeyOf(nsoCode);
        if (polarity == null) polarity = POSITIVE;
    }

    public boolean isPositive() { return POSITIVE.equals(polarity); }

    public boolean isResearch() { return ORIGIN_RESEARCH.equals(origin); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getAliasKey() { return aliasKey; }
    public void setAliasKey(String aliasKey) { this.aliasKey = aliasKey; }
    public String getNsoCode() { return nsoCode; }
    public void setNsoCode(String nsoCode) {
        this.nsoCode = nsoCode;
        this.codeKey = codeKeyOf(nsoCode);
    }
    public String getCodeKey() { return codeKey; }
    public String getTargetBrandKey() { return targetBrandKey; }
    public void setTargetBrandKey(String targetBrandKey) { this.targetBrandKey = targetBrandKey; }
    public String getPolarity() { return polarity; }
    public void setPolarity(String polarity) { this.polarity = polarity; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public Long getSourceProductId() { return sourceProductId; }
    public void setSourceProductId(Long sourceProductId) { this.sourceProductId = sourceProductId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
