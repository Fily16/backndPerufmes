package org.example.backendbvaberiaperfumes.service.allocation;

import org.example.backendbvaberiaperfumes.model.SupplierConstraint;

/**
 * Evalua una restriccion de proveedor sobre un carrito.
 * Tipo nuevo de restriccion = una implementacion nueva; el motor no cambia
 * (se registran por inyeccion de List&lt;ConstraintEvaluator&gt;, igual que los parsers).
 */
public interface ConstraintEvaluator {

    /** Tipo que atiende (SupplierConstraint.type). */
    String type();

    /**
     * Cuanto FALTA para satisfacer la restriccion (unidades propias del tipo:
     * USD para MIN_ORDER_USD, unidades para MIN_UNITS...). Satisfecha &lt;=&gt; deficit &lt;= 0.
     */
    double deficit(SupplierCart cart, SupplierConstraint c);
}
