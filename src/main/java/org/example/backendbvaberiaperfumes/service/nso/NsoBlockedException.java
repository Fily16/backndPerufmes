package org.example.backendbvaberiaperfumes.service.nso;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Se intento comprar (pedido publico, compra de tienda, promo) un perfume que con el gate NSO activo no se
 * puede vender. Extiende IllegalArgumentException a proposito: los catch existentes ya lo convierten en 400.
 *
 * El mensaje va al CLIENTE: nunca dice "NSO" ("«Marca Nombre» ya no está disponible..."). Los controladores
 * que quieran devolver {message, unavailableProductIds} usan getProductIds().
 */
public class NsoBlockedException extends IllegalArgumentException {

    private final List<Long> productIds;

    public NsoBlockedException(String message, Collection<Long> productIds) {
        super(message);
        this.productIds = Collections.unmodifiableList(new ArrayList<>(productIds == null ? List.of() : productIds));
    }

    /** Ids de los productos bloqueados (contrato: unavailableProductIds). */
    public List<Long> getProductIds() {
        return productIds;
    }

    /** Alias con el nombre del contrato JSON. */
    public List<Long> getUnavailableProductIds() {
        return productIds;
    }
}
