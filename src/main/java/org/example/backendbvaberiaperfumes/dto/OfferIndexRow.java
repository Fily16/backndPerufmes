package org.example.backendbvaberiaperfumes.dto;

/**
 * Fila compacta del indice de ofertas: TODO el catalogo en UNA consulta, sin cargar entidades.
 * Alimenta los filtros del admin de Productos (por proveedor, sold out, sin codigo, etc.)
 * sin pedir las ofertas producto por producto.
 */
public record OfferIndexRow(
        Long productId,
        Long supplierId,
        String supplierName,
        Boolean supplierActive,
        Boolean inStock,
        Double costUsd,
        String gtinStatus
) { }
