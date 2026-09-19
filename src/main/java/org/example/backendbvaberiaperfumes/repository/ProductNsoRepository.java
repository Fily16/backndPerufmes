package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.ProductNso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface ProductNsoRepository extends JpaRepository<ProductNso, Long> {

    /**
     * Base del gate: producto vigente, CON_NSO y codigo ACTIVO (aunque el rematch aun no haya corrido).
     * Dos consultas en vez de un parametro booleano: Postgres no siempre infiere el tipo de ":x = true".
     */
    @Query("select n.productId from ProductNso n, Product p, NsoRecord r "
            + "where n.productId = p.id and n.nsoCode = r.code and p.archived = false "
            + "and n.status = 'CON_NSO' and r.active = true")
    List<Long> findEligibleProductIds();

    /** Igual que findEligibleProductIds pero solo codigos de Peru (nso_accept_can_codes=false). */
    @Query("select n.productId from ProductNso n, Product p, NsoRecord r "
            + "where n.productId = p.id and n.nsoCode = r.code and p.archived = false "
            + "and n.status = 'CON_NSO' and r.active = true and r.code like '%PE'")
    List<Long> findEligiblePeruProductIds();

    /** Productos no archivados por estado; los que no tienen fila cuentan como SIN_VERIFICAR. */
    @Query("select coalesce(n.status, 'SIN_VERIFICAR'), count(p) from Product p "
            + "left join ProductNso n on n.productId = p.id where p.archived = false "
            + "group by coalesce(n.status, 'SIN_VERIFICAR')")
    List<Object[]> countCurrentProductsByStatus();

    /** Visibles hoy en la tienda ignorando el gate (summary.publicNow). */
    @Query("select count(p) from Product p where p.archived = false and p.available = true")
    long countPublicNow();

    /** De los visibles hoy, cuantos quedarian con el gate activo (summary.publicIfActivated). */
    @Query("select count(p) from Product p, ProductNso n, NsoRecord r "
            + "where n.productId = p.id and n.nsoCode = r.code and p.archived = false and p.available = true "
            + "and n.status = 'CON_NSO' and r.active = true")
    long countPublicIfActivated();

    @Query("select count(p) from Product p, ProductNso n, NsoRecord r "
            + "where n.productId = p.id and n.nsoCode = r.code and p.archived = false and p.available = true "
            + "and n.status = 'CON_NSO' and r.active = true and r.code like '%PE'")
    long countPublicIfActivatedPeru();

    /** Estados de productos NO archivados con ese status (GET /products?status=). */
    @Query("select n from ProductNso n, Product p where n.productId = p.id and p.archived = false and n.status = :status")
    List<ProductNso> findCurrentByStatus(@Param("status") String status);

    /** Productos no archivados sin fila en product_nso (SIN_VERIFICAR implicito). */
    @Query("select p from Product p where p.archived = false "
            + "and not exists (select 1 from ProductNso n where n.productId = p.id)")
    List<org.example.backendbvaberiaperfumes.model.Product> findCurrentProductsWithoutState();

    /** Ids de productos agrupados en estas marcas canonicas (hermanos para un rematch acotado). */
    @Query("select n.productId from ProductNso n where n.brandKey in :brandKeys")
    List<Long> findProductIdsByBrandKeyIn(@Param("brandKeys") Collection<String> brandKeys);

    long countByStatus(String status);

    List<ProductNso> findByStatus(String status);

    List<ProductNso> findByProductIdIn(Collection<Long> productIds);

    /** Productos enlazados a un codigo (para "afectados" al desactivar y linkedProducts). */
    List<ProductNso> findByNsoCodeAndStatus(String nsoCode, String status);

    List<ProductNso> findByNsoCode(String nsoCode);

    /** Productos de una marca que el matcher puede re-evaluar (no bloqueados por la admin). */
    List<ProductNso> findByBrandKeyAndLockedFalse(String brandKey);

    List<ProductNso> findByLockedTrue();

    @Query("select p.status, count(p) from ProductNso p group by p.status")
    List<Object[]> countGroupedByStatus();

    /**
     * Conteo por estado con TODAS las claves de ProductNso.STATUSES (0 si no hay).
     * Ojo: SIN_VERIFICAR aqui solo cuenta filas explicitas; los productos sin fila tambien lo son.
     */
    default Map<String, Long> countByStatusMap() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : ProductNso.STATUSES) out.put(s, 0L);
        for (Object[] row : countGroupedByStatus()) {
            out.put((String) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    @Query("select p.productId from ProductNso p where p.status = :status")
    List<Long> findProductIdsByStatus(@Param("status") String status);

    /** Ids CON_NSO (base del filtro publico con gate activo). */
    default List<Long> findConNsoProductIds() {
        return findProductIdsByStatus(ProductNso.STATUS_CON_NSO);
    }

    /** Ids CON_NSO cuyo codigo es de Peru (para nso_accept_can_codes=false). */
    @Query("select p.productId from ProductNso p where p.status = 'CON_NSO' and p.nsoCode like '%PE'")
    List<Long> findConNsoPeruProductIds();

    /** [nsoCode, cantidad de productos CON_NSO enlazados] por codigo. */
    @Query("select p.nsoCode, count(p) from ProductNso p where p.status = 'CON_NSO' and p.nsoCode is not null group by p.nsoCode")
    List<Object[]> countLinkedByCodeRows();

    default Map<String, Long> countLinkedByCode() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : countLinkedByCodeRows()) {
            out.put((String) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }
}
