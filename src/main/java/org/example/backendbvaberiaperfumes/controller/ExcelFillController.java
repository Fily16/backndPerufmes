package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.dto.FillReport;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.example.backendbvaberiaperfumes.service.excelfill.SupplierExcelFiller;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * "Completar Excel del proveedor": toma el Excel original del proveedor y le escribe las
 * cantidades a pedir (por UPC) según el resultado de "Ver qué comprar" (computeAllocation).
 * No recalcula nada: consume la asignación existente.
 */
@RestController
@RequestMapping("/api/admin")
public class ExcelFillController {

    private final AllocationService allocationService;
    private final SupplierRepository supplierRepo;
    private final SupplierExcelFiller filler;

    public ExcelFillController(AllocationService allocationService, SupplierRepository supplierRepo,
                               SupplierExcelFiller filler) {
        this.allocationService = allocationService;
        this.supplierRepo = supplierRepo;
        this.filler = filler;
    }

    @PostMapping(value = "/consolidados/{consolidadoId}/suppliers/{supplierId}/fill-excel",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> fillExcel(@PathVariable Long consolidadoId, @PathVariable Long supplierId,
                                       @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Archivo vacío o no enviado."));
        }
        Supplier supplier = supplierRepo.findById(supplierId).orElse(null);
        if (supplier == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Proveedor no encontrado."));
        }

        // Sub-asignación de ESTE proveedor (mismo motor que "Ver qué comprar").
        AllocationResponse alloc = allocationService.computeAllocation(consolidadoId);
        AllocationResponse.SupplierAllocation sa = alloc.suppliers.stream()
                .filter(s -> supplierId.equals(s.supplierId)).findFirst().orElse(null);

        Map<String, Integer> qtyByUpc = new HashMap<>();
        List<AllocationResponse.AllocationLine> withUpc = new ArrayList<>();
        List<FillReport.Missing> noUpc = new ArrayList<>();
        if (sa != null) {
            for (AllocationResponse.AllocationLine l : sa.lines) {
                String canon = l.gtin != null ? GtinCanonicalizer.canonicalize(l.gtin).canonical14 : null;
                if (canon != null) {
                    qtyByUpc.merge(canon, l.quantity, Integer::sum);
                    withUpc.add(l);
                } else {
                    noUpc.add(new FillReport.Missing(l.gtin, l.brand, l.name, l.quantity));
                }
            }
        }

        try {
            SupplierExcelFiller.FillResult res = filler.fill(file.getBytes(), supplier.getName(), qtyByUpc);
            FillReport report = res.report;
            report.noUpcLines = noUpc;
            for (AllocationResponse.AllocationLine l : withUpc) {
                String canon = GtinCanonicalizer.canonicalize(l.gtin).canonical14;
                if (!res.matchedUpcs.contains(canon)) {
                    report.notFound.add(new FillReport.Missing(l.gtin, l.brand, l.name, l.quantity));
                }
            }

            String orig = file.getOriginalFilename();
            String base = (orig != null ? orig.replaceAll("(?i)\\.xlsx?$", "") : "pedido").trim();
            if (base.isEmpty()) base = "pedido";
            String filename = base + " - PEDIDO.xlsx";

            Map<String, Object> body = new HashMap<>();
            body.put("filename", filename);
            body.put("fileBase64", Base64.getEncoder().encodeToString(res.bytes));
            body.put("report", report);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Error al completar el Excel: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }
}
