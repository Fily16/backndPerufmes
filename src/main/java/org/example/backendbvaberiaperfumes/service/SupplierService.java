package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Alta/edicion/desactivacion/eliminacion de proveedores y su REPERCUSION en todo el sistema:
 * al cambiar un proveedor se recalculan los precios de sus productos (el mas barato activo manda)
 * y se ocultan los que quedan sin ninguna oferta activa. Todo por UPC/GTIN, nunca por nombre.
 */
@Service
public class SupplierService {

    private final SupplierRepository supplierRepo;
    private final SupplierOfferRepository offerRepo;
    private final ProductRepository productRepo;
    private final ExcelImportService importService;

    public SupplierService(SupplierRepository supplierRepo, SupplierOfferRepository offerRepo,
                           ProductRepository productRepo, ExcelImportService importService) {
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.productRepo = productRepo;
        this.importService = importService;
    }

    public Supplier get(Long id) {
        return supplierRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado: " + id));
    }

    @Transactional
    public Supplier create(String name, Double minOrderUsd, Boolean priority, Boolean active) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("El nombre del proveedor es obligatorio.");
        name = name.trim();
        if (supplierRepo.findByName(name).isPresent())
            throw new IllegalArgumentException("Ya existe un proveedor con el nombre '" + name + "'.");
        Supplier s = new Supplier(name, minOrderUsd != null ? minOrderUsd : 0.0, Boolean.TRUE.equals(priority));
        s.setActive(active == null || active);
        return supplierRepo.save(s);
    }

    @Transactional
    public Supplier update(Long id, String name, Double min, Boolean priority, Boolean active) {
        Supplier s = get(id);
        boolean wasActive = Boolean.TRUE.equals(s.getActive());
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            supplierRepo.findByName(trimmed).ifPresent(other -> {
                if (!other.getId().equals(id))
                    throw new IllegalArgumentException("Ya existe otro proveedor con el nombre '" + trimmed + "'.");
            });
            s.setName(trimmed);
        }
        if (min != null) s.setMinOrderUsd(min);
        if (priority != null) s.setPriorityToReachMin(priority);
        if (active != null) s.setActive(active);
        supplierRepo.save(s);
        if (active != null && active != wasActive) applyRipple(id);
        return s;
    }

    @Transactional
    public Supplier setActive(Long id, boolean active) {
        Supplier s = get(id);
        s.setActive(active);
        supplierRepo.save(s);
        applyRipple(id);
        return s;
    }

    /** Borrado permanente: elimina sus ofertas + el proveedor, luego recalcula precios. */
    @Transactional
    public void delete(Long id) {
        Supplier s = get(id);
        List<Long> affected = offerRepo.findProductIdsBySupplier(id);
        offerRepo.deleteBySupplier_Id(id);
        supplierRepo.delete(s);
        recompute(affected);
    }

    private void applyRipple(Long supplierId) {
        recompute(offerRepo.findProductIdsBySupplier(supplierId));
    }

    private void recompute(List<Long> productIds) {
        for (Long pid : productIds) {
            productRepo.findById(pid).ifPresent(importService::recomputeProductPrice);
        }
    }
}
