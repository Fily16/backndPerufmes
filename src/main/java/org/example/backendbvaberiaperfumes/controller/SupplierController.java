package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.SupplierRequest;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.example.backendbvaberiaperfumes.repository.SupplierConstraintRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
import org.example.backendbvaberiaperfumes.service.ImportBatchService;
import org.example.backendbvaberiaperfumes.service.SupplierService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/suppliers")
public class SupplierController {

    private final SupplierRepository supplierRepo;
    private final SupplierService supplierService;
    private final ImportBatchService batchService;
    private final ExcelImportService importService;
    private final SupplierConstraintRepository constraintRepo;

    public SupplierController(SupplierRepository supplierRepo, SupplierService supplierService,
                              ImportBatchService batchService, ExcelImportService importService,
                              SupplierConstraintRepository constraintRepo) {
        this.supplierRepo = supplierRepo;
        this.supplierService = supplierService;
        this.batchService = batchService;
        this.importService = importService;
        this.constraintRepo = constraintRepo;
    }

    @GetMapping
    public List<Supplier> getAll() {
        return supplierRepo.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SupplierRequest req) {
        try {
            Supplier s = supplierService.create(req.name, req.minOrderUsd, req.priorityToReachMin, req.active);
            return ResponseEntity.ok(s);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody SupplierRequest req) {
        try {
            Supplier s = supplierService.update(id, req.name, req.minOrderUsd, req.priorityToReachMin, req.active);
            return ResponseEntity.ok(s);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Desactivar/activar (mecanismo principal para "quitar" un proveedor, reversible). */
    @PatchMapping("/{id}/active")
    public ResponseEntity<?> setActive(@PathVariable Long id, @RequestParam("value") boolean value) {
        try {
            return ResponseEntity.ok(supplierService.setActive(id, value));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Borrado permanente (elimina sus ofertas y recalcula precios). */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            supplierService.delete(id);
            return ResponseEntity.ok(Map.of("message", "Proveedor eliminado."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // --- Restricciones de compra del proveedor (minimos como datos) ---

    @GetMapping("/{id}/constraints")
    public List<SupplierConstraint> constraints(@PathVariable Long id) {
        return constraintRepo.findBySupplier_Id(id);
    }

    @PostMapping("/{id}/constraints")
    public ResponseEntity<?> addConstraint(@PathVariable Long id, @RequestBody SupplierConstraint body) {
        Supplier s = supplierRepo.findById(id).orElse(null);
        if (s == null) return ResponseEntity.badRequest().body(Map.of("message", "Proveedor no existe"));
        if (body.getType() == null || body.getValueNum() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "type y valueNum son obligatorios"));
        }
        SupplierConstraint c = new SupplierConstraint(s, body.getType().toUpperCase(), body.getValueNum());
        c.setScopeJson(body.getScopeJson());
        c.setActive(body.getActive() == null || body.getActive());
        return ResponseEntity.ok(constraintRepo.save(c));
    }

    @PutMapping("/{id}/constraints/{constraintId}")
    public ResponseEntity<?> updateConstraint(@PathVariable Long id, @PathVariable Long constraintId,
                                              @RequestBody SupplierConstraint body) {
        SupplierConstraint c = constraintRepo.findById(constraintId).orElse(null);
        if (c == null || !c.getSupplier().getId().equals(id)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Restriccion no existe para este proveedor"));
        }
        if (body.getType() != null) c.setType(body.getType().toUpperCase());
        if (body.getValueNum() != null) c.setValueNum(body.getValueNum());
        if (body.getScopeJson() != null) c.setScopeJson(body.getScopeJson());
        if (body.getActive() != null) c.setActive(body.getActive());
        return ResponseEntity.ok(constraintRepo.save(c));
    }

    @DeleteMapping("/{id}/constraints/{constraintId}")
    public ResponseEntity<?> deleteConstraint(@PathVariable Long id, @PathVariable Long constraintId) {
        SupplierConstraint c = constraintRepo.findById(constraintId).orElse(null);
        if (c == null || !c.getSupplier().getId().equals(id)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Restriccion no existe para este proveedor"));
        }
        constraintRepo.delete(c);
        return ResponseEntity.ok(Map.of("message", "Restriccion eliminada"));
    }

    /** Vista previa (NO publica): sube el Excel, lo parsea y devuelve el detalle + batchId. */
    @PostMapping(value = "/{id}/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewImport(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Archivo vacio o no enviado."));
        }
        try {
            ImportPreview preview = batchService.preview(id, file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error al leer el Excel: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /** Import directo (compatibilidad): parsea y publica en un solo paso. */
    @PostMapping(value = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importExcel(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Archivo vacio o no enviado."));
        }
        try (InputStream is = file.getInputStream()) {
            ImportSummary summary = importService.importExcel(id, is);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error al procesar el Excel: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }
}
