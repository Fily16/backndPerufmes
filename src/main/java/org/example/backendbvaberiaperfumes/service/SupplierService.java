package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierConstraintRepository;
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
    private final SupplierConstraintRepository constraintRepo;

    public SupplierService(SupplierRepository supplierRepo, SupplierOfferRepository offerRepo,
                           ProductRepository productRepo, ExcelImportService importService,
                           SupplierConstraintRepository constraintRepo) {
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.productRepo = productRepo;
        this.importService = importService;
        this.constraintRepo = constraintRepo;
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
        s = supplierRepo.save(s);
        syncMinOrderConstraint(s);
        return s;
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
        if (min != null) syncMinOrderConstraint(s);
        if (active != null && active != wasActive) applyRipple(id);
        return s;
    }

    /**
     * Mantiene coherente el campo legacy minOrderUsd con la restriccion MIN_ORDER_USD
     * (la tabla supplier_constraints es lo que lee el optimizador de compra).
     */
    private void syncMinOrderConstraint(Supplier s) {
        var existing = constraintRepo.findBySupplier_Id(s.getId()).stream()
                .filter(c -> "MIN_ORDER_USD".equals(c.getType()))
                .findFirst();
        double min = s.getMinOrderUsd() != null ? s.getMinOrderUsd() : 0;
        if (min > 0) {
            var c = existing.orElseGet(() ->
                    new org.example.backendbvaberiaperfumes.model.SupplierConstraint(s, "MIN_ORDER_USD", min));
            c.setValueNum(min);
            c.setActive(true);
            constraintRepo.save(c);
        } else {
            existing.ifPresent(c -> {
                c.setActive(false);
                constraintRepo.save(c);
            });
        }
    }

    @Transactional
    public Supplier setActive(Long id, boolean active) {
        Supplier s = get(id);
        s.setActive(active);
        supplierRepo.save(s);
        applyRipple(id);
        return s;
    }

    /** Borrado permanente: elimina sus ofertas + restricciones + el proveedor, luego recalcula precios. */
    @Transactional
    public void delete(Long id) {
        Supplier s = get(id);
        List<Long> affected = offerRepo.findProductIdsBySupplier(id);
        offerRepo.deleteBySupplier_Id(id);
        constraintRepo.deleteBySupplier_Id(id);
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
