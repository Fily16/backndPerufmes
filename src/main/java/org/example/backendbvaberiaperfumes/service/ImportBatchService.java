package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.dto.ColumnMapping;
import org.example.backendbvaberiaperfumes.dto.ImportPreview;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.dto.PublishRequest;
import org.example.backendbvaberiaperfumes.model.ImportBatch;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.repository.ImportBatchRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

/**
 * Staging de importaciones: subir -> previsualizar (sin tocar la tienda) -> corregir columnas ->
 * publicar (recien ahi se ve en el catalogo) o descartar. El Excel se guarda en el batch para
 * re-parsear con columnas corregidas.
 */
@Service
public class ImportBatchService {

    private final ImportBatchRepository batchRepo;
    private final SupplierRepository supplierRepo;
    private final ExcelImportService importService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImportBatchService(ImportBatchRepository batchRepo, SupplierRepository supplierRepo,
                              ExcelImportService importService) {
        this.batchRepo = batchRepo;
        this.supplierRepo = supplierRepo;
        this.importService = importService;
    }

    @Transactional
    public ImportPreview preview(Long supplierId, String filename, byte[] bytes) throws Exception {
        Supplier supplier = supplier(supplierId);
        // Descartar previews PENDING anteriores del mismo proveedor (solo una en curso).
        for (ImportBatch old : batchRepo.findBySupplierIdAndStatus(supplierId, "PENDING")) {
            old.setStatus("DISCARDED");
            old.setFileBase64(null);
            batchRepo.save(old);
        }
        ExcelImportService.ParsedData pd = importService.parse(supplier, bytes, null);
        ImportPreview preview = importService.buildPreview(supplier, pd);

        ImportBatch batch = new ImportBatch();
        batch.setSupplierId(supplierId);
        batch.setFilename(filename);
        batch.setFileBase64(Base64.getEncoder().encodeToString(bytes));
        batch.setStatus("PENDING");
        batch.setColumnMapJson(pd.mapping != null ? mapper.writeValueAsString(pd.mapping) : null);
        batchRepo.save(batch);

        preview.batchId = batch.getId();
        return preview;
    }

    @Transactional
    public ImportPreview reparse(Long batchId, ColumnMapping mapping) throws Exception {
        ImportBatch batch = pending(batchId);
        Supplier supplier = supplier(batch.getSupplierId());
        byte[] bytes = Base64.getDecoder().decode(batch.getFileBase64());
        ExcelImportService.ParsedData pd = importService.parse(supplier, bytes, mapping);
        ImportPreview preview = importService.buildPreview(supplier, pd);
        batch.setColumnMapJson(pd.mapping != null ? mapper.writeValueAsString(pd.mapping) : null);
        batchRepo.save(batch);
        preview.batchId = batch.getId();
        return preview;
    }

    @Transactional
    public ImportSummary publish(Long batchId, PublishRequest req) throws Exception {
        ImportBatch batch = pending(batchId);
        Supplier supplier = supplier(batch.getSupplierId());
        byte[] bytes = Base64.getDecoder().decode(batch.getFileBase64());
        ColumnMapping mapping = batch.getColumnMapJson() != null
                ? mapper.readValue(batch.getColumnMapJson(), ColumnMapping.class) : null;
        ExcelImportService.ParsedData pd = importService.parse(supplier, bytes, mapping);
        applyOverrides(pd.rows, req);
        ImportSummary summary = importService.commit(supplier, pd.rows);
        batch.setStatus("PUBLISHED");
        batch.setFileBase64(null); // liberar espacio
        batchRepo.save(batch);
        return summary;
    }

    /** Aplica marca/nombre/ml elegidos a mano por el admin (por indice de fila del preview). */
    private void applyOverrides(java.util.List<ParsedRow> rows, PublishRequest req) {
        if (req == null || req.overrides == null) return;
        for (var e : req.overrides.entrySet()) {
            int i;
            try { i = Integer.parseInt(e.getKey()); } catch (NumberFormatException ex) { continue; }
            if (i < 0 || i >= rows.size()) continue;
            ParsedRow r = rows.get(i);
            PublishRequest.RowOverride ov = e.getValue();
            if (ov == null) continue;
            if (ov.brand != null && !ov.brand.isBlank()) r.brand = ov.brand.trim();
            if (ov.name != null && !ov.name.isBlank()) r.name = ov.name.trim();
            if (ov.ml != null) r.ml = ov.ml;
            if (ov.imageUrl != null && !ov.imageUrl.isBlank()) r.imageUrl = ov.imageUrl.trim();
        }
    }

    @Transactional
    public void discard(Long batchId) {
        batchRepo.findById(batchId).ifPresent(b -> {
            b.setStatus("DISCARDED");
            b.setFileBase64(null);
            batchRepo.save(b);
        });
    }

    private Supplier supplier(Long id) {
        return supplierRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado: " + id));
    }

    private ImportBatch pending(Long id) {
        ImportBatch b = batchRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Importacion no encontrada: " + id));
        if (!"PENDING".equals(b.getStatus()))
            throw new IllegalArgumentException("La importacion ya fue " + b.getStatus().toLowerCase() + ".");
        if (b.getFileBase64() == null)
            throw new IllegalArgumentException("La importacion ya no tiene el archivo (expirada).");
        return b;
    }
}
