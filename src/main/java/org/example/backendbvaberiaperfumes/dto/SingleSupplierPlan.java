package org.example.backendbvaberiaperfumes.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * "Comprar solo en un proveedor": re-partición del resultado de la asignación normal para
 * consolidar la mayor cantidad posible de productos en UN proveedor objetivo, sin recalcular
 * ni tocar el motor. Es una VISTA sobre {@link AllocationResponse} (usa {@code AllocationLine.alternatives},
 * que ya lista todos los proveedores con stock por producto). Parametrizado por proveedor = escalable.
 */
public class SingleSupplierPlan {

    public Long consolidadoId;
    public Long targetSupplierId;
    public String targetSupplierName;

    /** Grupo 1: productos que SÍ se compran en el proveedor objetivo (incluye reasignados). */
    public List<BuyLine> buy = new ArrayList<>();
    /** Grupo 3: productos que tenían oferta en otro proveedor pero NO en el objetivo. */
    public List<CouldNotBuy> couldNotBuy = new ArrayList<>();

    public int buyPerfumes;
    public int buyUnits;
    public double buySubtotalUsd;

    public static class BuyLine {
        public Long productId;
        public String brand;
        public String name;
        public String gtin;
        public Integer ml;
        public int quantity;
        public double unitCostUsd;
        public double subtotalUsd;
        /** Si venía asignado a OTRO proveedor y se movió al objetivo (null si ya estaba en el objetivo). */
        public Long movedFromSupplierId;
        public String movedFromSupplierName;
    }

    public static class CouldNotBuy {
        public Long productId;
        public String brand;
        public String name;
        public String gtin;
        public int quantity;
        /** Proveedor donde la asignación normal sí lo tenía (para trazabilidad). */
        public String currentSupplierName;
        public String reason;
    }
}
