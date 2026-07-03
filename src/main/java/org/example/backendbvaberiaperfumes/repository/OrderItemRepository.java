package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByProductId(Long productId);

    List<OrderItem> findByOrderId(Long orderId);

    @Modifying
    @Query("DELETE FROM OrderItem oi WHERE oi.product.id = :productId")
    void deleteByProductId(@Param("productId") Long productId);

    /**
     * Co-compra real: productos que aparecen en los mismos pedidos que :productId,
     * ordenados por cuantos pedidos los comparten. Base del "Frecuentemente pedidos juntos".
     * Devuelve filas [productId (Long), pedidosCompartidos (Long)].
     */
    @Query("select oi2.product.id, count(distinct oi1.order.id) " +
           "from OrderItem oi1, OrderItem oi2 " +
           "where oi1.order.id = oi2.order.id " +
           "and oi1.product.id = :productId and oi2.product.id <> :productId " +
           "group by oi2.product.id " +
           "order by count(distinct oi1.order.id) desc")
    List<Object[]> findCoPurchasedProductIds(@Param("productId") Long productId);

    /** Mas vendidos globales: filas [productId (Long), unidades (Long)]. */
    @Query("select oi.product.id, sum(oi.quantity) " +
           "from OrderItem oi group by oi.product.id order by sum(oi.quantity) desc")
    List<Object[]> findBestSellerProductIds();

    /** Todos los items de un producto dentro de un consolidado (para el picking agregado del desglose). */
    @Query("select oi from OrderItem oi where oi.product.id = :productId " +
           "and oi.order.consolidado.id = :consolidadoId")
    List<OrderItem> findByProductInConsolidado(@Param("productId") Long productId,
                                               @Param("consolidadoId") Long consolidadoId);
}
