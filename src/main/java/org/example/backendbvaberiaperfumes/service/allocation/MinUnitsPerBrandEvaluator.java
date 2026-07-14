package org.example.backendbvaberiaperfumes.service.allocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.springframework.stereotype.Component;

/** Minimo de unidades de UNA marca. scopeJson: {"brand":"Lattafa"}. */
@Component
public class MinUnitsPerBrandEvaluator implements ConstraintEvaluator {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() { return "MIN_UNITS_PER_BRAND"; }

    @Override
    public double deficit(SupplierCart cart, SupplierConstraint c) {
        double min = c.getValueNum() != null ? c.getValueNum() : 0;
        String brand = scopeBrand(c);
        if (brand == null) return 0; // sin marca definida: restriccion mal configurada, no bloquea
        int units = cart.unitsByBrand.getOrDefault(brand.toLowerCase(), 0);
        return min - units;
    }

    /** Marca del alcance (usada tambien por el optimizador para elegir movimientos). */
    public String scopeBrand(SupplierConstraint c) {
        if (c.getScopeJson() == null || c.getScopeJson().isBlank()) return null;
        try {
            JsonNode n = mapper.readTree(c.getScopeJson());
            return n.has("brand") ? n.get("brand").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
