package org.example.backendbvaberiaperfumes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Registro del catalogo de Notificaciones Sanitarias (NSO) de perfumes en Peru.
 * Se carga desde el panel (xlsx o csv) y se puede re-subir: upsert por codigo, NUNCA se borra
 * (un codigo ausente en la ultima carga queda con inLastUpload=false y se avisa).
 *
 * Implementa Persistable: la clave es el codigo (asignado, no generado), asi save() de un registro
 * nuevo hace INSERT directo sin el SELECT previo que haria merge() (importante con 1,700 filas contra
 * la BD remota). El flag isNew se apaga solo al cargar (@PostLoad) o al insertar (@PostPersist).
 */
@Entity
@Table(name = "nso_records", indexes = {
        @Index(name = "idx_nso_records_brand_key", columnList = "brand_key"),
        @Index(name = "idx_nso_records_ean", columnList = "ean"),
        @Index(name = "idx_nso_records_active", columnList = "active")
})
public class NsoRecord implements Persistable<String> {

    public static final String SOURCE_CSV = "CSV";
    public static final String SOURCE_ADUANET = "ADUANET";
    public static final String SOURCE_XLSX = "XLSX";
    public static final String SOURCE_MANUAL = "MANUAL";

    /** Codigo canonico, ej. NSOC70523-25PE (ver NsoCode). */
    @Id
    @Column(name = "code", length = 20)
    private String code;

    /** Marca tal como viene en el catalogo (ej. "ANTONIO BANDERAS"). */
    @Column(nullable = false, length = 150)
    private String brand;

    /** Marca normalizada (NsoKeys.brandKey) para agrupar e indexar. */
    @Column(name = "brand_key", nullable = false, length = 150)
    private String brandKey;

    /** Nombre del producto declarado en aduanas (a veces cortado a 35 letras). */
    @Column(name = "declared_name", nullable = false, length = 500)
    private String declaredName;

    @Column(length = 300)
    private String titular;

    /** RUC del titular como TEXTO (nunca numero: se perderian ceros y formato). */
    @Column(length = 20)
    private String ruc;

    /** Disenador | Arabe (columna Tipo del xlsx o categoria del csv). */
    @Column(length = 40)
    private String tipo;

    @Column(length = 40)
    private String categoria;

    /** Pais de origen de la mercaderia (ej. "France"). No confundir con country (pais del codigo). */
    @Column(length = 80)
    private String origen;

    /** GTIN-14 canonico, SOLO si el EAN del catalogo valida checksum GS1. */
    @Column(length = 14)
    private String ean;

    private Double kg;

    @Column(name = "fob_usd")
    private Double fobUsd;

    @Column(name = "usd_kg")
    private Double usdKg;

    private Integer series;

    /** Ultima importacion vista en Aduanet, DD/MM/YYYY. */
    @Column(name = "last_import_date", length = 10)
    private String lastImportDate;

    /** Anio calculado desde el codigo con pivot (NsoCode.year); se ignora anio_nso del csv (roto). */
    @Column(name = "nso_year")
    private Integer nsoYear;

    /** Pais CAN del codigo: PE | CO | BO | EC. */
    @Column(length = 2)
    private String country;

    /** CSV | ADUANET | XLSX | MANUAL */
    @Column(nullable = false, length = 10)
    private String source = SOURCE_CSV;

    /** Un codigo desactivado por el admin no cuenta como NSO (sus productos se recalculan). */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private Boolean active = true;

    /** false si el codigo no vino en la ultima carga del catalogo (solo aviso, no se borra). */
    @Column(name = "in_last_upload", nullable = false, columnDefinition = "boolean default true")
    private Boolean inLastUpload = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @JsonIgnore
    private boolean newEntity = true;

    public NsoRecord() {}

    public NsoRecord(String code, String brand, String brandKey, String declaredName) {
        this.code = code;
        this.brand = brand;
        this.brandKey = brandKey;
        this.declaredName = declaredName;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (active == null) active = true;
        if (inLastUpload == null) inLastUpload = true;
        if (source == null) source = SOURCE_CSV;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        this.newEntity = false;
    }

    // --- Persistable ---

    @Override
    @JsonIgnore
    public String getId() { return code; }

    @Override
    @JsonIgnore
    public boolean isNew() { return newEntity; }

    /**
     * Copia a este registro los datos de catalogo de otro (el recien leido del archivo) SIN borrar:
     * un valor null/vacio en el archivo no pisa el que ya estaba. No toca code, active, inLastUpload
     * ni timestamps. Devuelve true si cambio algo (para contar updated vs unchanged).
     */
    public boolean applyCatalogData(NsoRecord src) {
        if (src == null) return false;
        boolean changed = false;
        if (differs(brand, src.brand)) { brand = src.brand; changed = true; }
        if (differs(brandKey, src.brandKey)) { brandKey = src.brandKey; changed = true; }
        if (differs(declaredName, src.declaredName)) { declaredName = src.declaredName; changed = true; }
        if (differs(titular, src.titular)) { titular = src.titular; changed = true; }
        if (differs(ruc, src.ruc)) { ruc = src.ruc; changed = true; }
        if (differs(tipo, src.tipo)) { tipo = src.tipo; changed = true; }
        if (differs(categoria, src.categoria)) { categoria = src.categoria; changed = true; }
        if (differs(origen, src.origen)) { origen = src.origen; changed = true; }
        if (differs(ean, src.ean)) { ean = src.ean; changed = true; }
        if (differs(kg, src.kg)) { kg = src.kg; changed = true; }
        if (differs(fobUsd, src.fobUsd)) { fobUsd = src.fobUsd; changed = true; }
        if (differs(usdKg, src.usdKg)) { usdKg = src.usdKg; changed = true; }
        if (differs(series, src.series)) { series = src.series; changed = true; }
        if (differs(lastImportDate, src.lastImportDate)) { lastImportDate = src.lastImportDate; changed = true; }
        if (differs(nsoYear, src.nsoYear)) { nsoYear = src.nsoYear; changed = true; }
        if (differs(country, src.country)) { country = src.country; changed = true; }
        if (differs(source, src.source)) { source = src.source; changed = true; }
        return changed;
    }

    /** true si el valor entrante trae dato y es distinto del actual. */
    private static boolean differs(Object current, Object incoming) {
        if (incoming == null) return false;
        if (incoming instanceof String s && s.isBlank()) return false;
        return !Objects.equals(current, incoming);
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getBrandKey() { return brandKey; }
    public void setBrandKey(String brandKey) { this.brandKey = brandKey; }
    public String getDeclaredName() { return declaredName; }
    public void setDeclaredName(String declaredName) { this.declaredName = declaredName; }
    public String getTitular() { return titular; }
    public void setTitular(String titular) { this.titular = titular; }
    public String getRuc() { return ruc; }
    public void setRuc(String ruc) { this.ruc = ruc; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }
    public String getOrigen() { return origen; }
    public void setOrigen(String origen) { this.origen = origen; }
    public String getEan() { return ean; }
    public void setEan(String ean) { this.ean = ean; }
    public Double getKg() { return kg; }
    public void setKg(Double kg) { this.kg = kg; }
    public Double getFobUsd() { return fobUsd; }
    public void setFobUsd(Double fobUsd) { this.fobUsd = fobUsd; }
    public Double getUsdKg() { return usdKg; }
    public void setUsdKg(Double usdKg) { this.usdKg = usdKg; }
    public Integer getSeries() { return series; }
    public void setSeries(Integer series) { this.series = series; }
    public String getLastImportDate() { return lastImportDate; }
    public void setLastImportDate(String lastImportDate) { this.lastImportDate = lastImportDate; }
    public Integer getNsoYear() { return nsoYear; }
    public void setNsoYear(Integer nsoYear) { this.nsoYear = nsoYear; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public Boolean getInLastUpload() { return inLastUpload; }
    public void setInLastUpload(Boolean inLastUpload) { this.inLastUpload = inLastUpload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(code, ((NsoRecord) o).code);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }
}
