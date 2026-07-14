package org.example.backendbvaberiaperfumes.service.allocation;

import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.springframework.stereotype.Component;

/** Pedido minimo en USD (Zimaxx: 2000). */
@Component
public class MinOrderUsdEvaluator implements ConstraintEvaluator {

    @Override
    public String type() { return "MIN_ORDER_USD"; }

    @Override
    public double deficit(SupplierCart cart, SupplierConstraint c) {
        double min = c.getValueNum() != null ? c.getValueNum() : 0;
        return min - cart.subtotalUsd;
    }
}
