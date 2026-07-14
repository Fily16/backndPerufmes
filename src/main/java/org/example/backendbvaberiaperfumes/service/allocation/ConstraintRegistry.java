package org.example.backendbvaberiaperfumes.service.allocation;

import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Registro de evaluadores por tipo (inyeccion de todos los beans ConstraintEvaluator). */
@Component
public class ConstraintRegistry {

    private final Map<String, ConstraintEvaluator> byType = new HashMap<>();

    public ConstraintRegistry(List<ConstraintEvaluator> evaluators) {
        for (ConstraintEvaluator e : evaluators) {
            byType.put(e.type(), e);
        }
    }

    /** Deficit de la restriccion; tipos desconocidos no bloquean (0). */
    public double deficit(SupplierCart cart, SupplierConstraint c) {
        ConstraintEvaluator e = byType.get(c.getType());
        return e != null ? e.deficit(cart, c) : 0;
    }

    public boolean supports(String type) {
        return byType.containsKey(type);
    }
}
