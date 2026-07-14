package org.example.backendbvaberiaperfumes.service.allocation;

import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.springframework.stereotype.Component;

/** Minimo de unidades sin importar cuales (FragranceSense: 48). */
@Component
public class MinUnitsEvaluator implements ConstraintEvaluator {

    @Override
    public String type() { return "MIN_UNITS"; }

    @Override
    public double deficit(SupplierCart cart, SupplierConstraint c) {
        double min = c.getValueNum() != null ? c.getValueNum() : 0;
        return min - cart.units;
    }
}
