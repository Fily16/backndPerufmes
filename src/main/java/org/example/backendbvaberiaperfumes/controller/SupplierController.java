package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
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
    private final ExcelImportService importService;

    public SupplierController(SupplierRepository supplierRepo, ExcelImportService importService) {
        this.supplierRepo = supplierRepo;
        this.importService = importService;
    }

    @GetMapping
    public List<Supplier> getAll() {
        return supplierRepo.findAll();
    }

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
