package org.example.backendbvaberiaperfumes.service.allocation;

import java.util.HashMap;
import java.util.Map;

/** Vista agregada de lo asignado a UN proveedor, para evaluar sus restricciones. */
public class SupplierCart {

    public double subtotalUsd;
    public int units;
    public Map<String, Integer> unitsByBrand = new HashMap<>();

    public void add(double unitCostUsd, int qty, String brand) {
        subtotalUsd += unitCostUsd * qty;
        units += qty;
        if (brand != null) {
            unitsByBrand.merge(brand.toLowerCase(), qty, Integer::sum);
        }
    }
}
