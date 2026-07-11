package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.ColumnMapping;
import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.PublishRequest;
import org.example.backendbvaberiaperfumes.service.ImportBatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Operaciones sobre una importacion en staging: re-analizar columnas, publicar o descartar. */
@RestController
@RequestMapping("/api/admin/import-batches")
public class ImportBatchController {

    private final ImportBatchService batchService;

    public ImportBatchController(ImportBatchService batchService) {
        this.batchService = batchService;
    }

    /** Re-parsea el Excel guardado con las columnas corregidas por el admin. */
    @PostMapping("/{id}/reparse")
    public ResponseEntity<?> reparse(@PathVariable Long id, @RequestBody ColumnMapping mapping) {
        try {
            ImportPreview preview = batchService.reparse(id, mapping);
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error al re-analizar: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /** Publica a la tienda: recien aqui se ve en el catalogo del cliente. */
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id, @RequestBody(required = false) PublishRequest req) {
        try {
            ImportSummary summary = batchService.publish(id, req);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error al publicar: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /** Descarta la vista previa (no cambia nada en la tienda). */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> discard(@PathVariable Long id) {
        batchService.discard(id);
        return ResponseEntity.ok(Map.of("message", "Importacion descartada."));
    }
}
