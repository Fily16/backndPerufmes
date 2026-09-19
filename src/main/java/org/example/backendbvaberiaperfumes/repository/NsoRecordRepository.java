package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.NsoRecord;
import org.example.backendbvaberiaperfumes.service.nso.NsoKeys;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public interface NsoRecordRepository extends JpaRepository<NsoRecord, String> {

    long countByActiveTrue();

    List<NsoRecord> findByActiveTrue();

    List<NsoRecord> findByBrandKey(String brandKey);

    List<NsoRecord> findByBrandKeyAndActiveTrue(String brandKey);

    List<NsoRecord> findByCodeIn(Collection<String> codes);

    /** Registros cuyo EAN (GTIN-14 validado) coincide. */
    List<NsoRecord> findByEan(String ean);

    /** Codigos que no vinieron en la ultima carga (aviso "ya no estan en tu lista"). */
    List<NsoRecord> findByInLastUploadFalseOrderByBrandKeyAscCodeAsc();

    /** Marcas con al menos un codigo activo. */
    @Query("select distinct r.brandKey from NsoRecord r where r.active = true")
    List<String> findActiveBrandKeys();

    /**
     * Busqueda paginada del catalogo. q ya en minusculas ("" = sin filtro) sobre codigo, marca, nombre
     * declarado, titular y RUC; brandKey exacto ("" = todas). Usar searchCatalog() que normaliza.
     * (Se usa '' en vez de null a proposito: Postgres no infiere el tipo de un parametro null.)
     */
    @Query(value = "select r from NsoRecord r where "
            + "(:q = '' or lower(r.code) like concat('%', :q, '%') "
            + "  or lower(r.brand) like concat('%', :q, '%') "
            + "  or lower(r.declaredName) like concat('%', :q, '%') "
            + "  or lower(coalesce(r.titular, '')) like concat('%', :q, '%') "
            + "  or coalesce(r.ruc, '') like concat('%', :q, '%')) "
            + "and (:brandKey = '' or r.brandKey = :brandKey)",
            countQuery = "select count(r) from NsoRecord r where "
            + "(:q = '' or lower(r.code) like concat('%', :q, '%') "
            + "  or lower(r.brand) like concat('%', :q, '%') "
            + "  or lower(r.declaredName) like concat('%', :q, '%') "
            + "  or lower(coalesce(r.titular, '')) like concat('%', :q, '%') "
            + "  or coalesce(r.ruc, '') like concat('%', :q, '%')) "
            + "and (:brandKey = '' or r.brandKey = :brandKey)")
    Page<NsoRecord> search(@Param("q") String q, @Param("brandKey") String brandKey, Pageable pageable);

    /**
     * GET /catalog?q=&brand=&page=&size=: normaliza q (minusculas, sin espacios en los bordes) y la marca
     * (NsoKeys.brandKey acepta tanto "Antonio Banderas" como "antonio banderas"); orden marca, codigo.
     */
    default Page<NsoRecord> searchCatalog(String q, String brand, int page, int size) {
        String qq = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String bk = NsoKeys.brandKey(brand);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 50 : size, 500));
        return search(qq, bk, PageRequest.of(Math.max(0, page), safeSize,
                Sort.by("brandKey").ascending().and(Sort.by("code").ascending())));
    }
}
