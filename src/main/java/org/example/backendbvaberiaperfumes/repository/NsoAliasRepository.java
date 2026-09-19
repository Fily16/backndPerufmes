package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.NsoAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NsoAliasRepository extends JpaRepository<NsoAlias, Long> {

    /** Todos los alias (cualquier codigo/polaridad/origen) de una clave. */
    List<NsoAlias> findByKindAndAliasKey(String kind, String aliasKey);

    /** Carga en bloque para un rematch: ej. todos los GTIN/SKU de las ofertas de un lote de productos. */
    List<NsoAlias> findByKindAndAliasKeyIn(String kind, Collection<String> aliasKeys);

    List<NsoAlias> findByKind(String kind);

    List<NsoAlias> findByKindAndPolarity(String kind, String polarity);

    List<NsoAlias> findByNsoCode(String nsoCode);

    List<NsoAlias> findBySourceProductId(Long sourceProductId);

    List<NsoAlias> findByOrigin(String origin);

    Optional<NsoAlias> findByKindAndAliasKeyAndCodeKey(String kind, String aliasKey, String codeKey);

    /** Busca por la clave unica (kind, aliasKey, nsoCode); nsoCode null = alias BRAND. */
    default Optional<NsoAlias> findUnique(String kind, String aliasKey, String nsoCode) {
        return findByKindAndAliasKeyAndCodeKey(kind, aliasKey, NsoAlias.codeKeyOf(nsoCode));
    }

    /** Alias BRAND de una marca de proveedor ya normalizada (a lo sumo uno por la clave unica). */
    default Optional<NsoAlias> findBrandAlias(String supplierBrandKey) {
        return findUnique(NsoAlias.KIND_BRAND, supplierBrandKey, null);
    }

    /** Reemplazo de los links de investigacion en cada carga del catalogo (origin RESEARCH). */
    @Modifying
    @Transactional
    @Query("delete from NsoAlias a where a.origin = :origin")
    int deleteByOrigin(@Param("origin") String origin);
}
