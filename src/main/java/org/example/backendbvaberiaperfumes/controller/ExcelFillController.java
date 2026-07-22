package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.dto.FillReport;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
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
    private final SupplierOfferRepository offerRepo;
    private final SupplierExcelFiller filler;

    public ExcelFillController(AllocationService allocationService, SupplierRepository supplierRepo,
                               SupplierOfferRepository offerRepo, SupplierExcelFiller filler) {
        this.allocationService = allocationService;
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
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

        // Claves de ubicación EXACTAS por producto, tomadas de la importación del proveedor
        // (mismo origen que el Excel): dígitos crudos del código, SKU y título tal como se importó.
        Map<Long, SupplierOffer> offerByProduct = new HashMap<>();
        for (SupplierOffer o : offerRepo.findBySupplier_Id(supplierId)) {
            Long pid = o.getProduct() != null ? o.getProduct().getId() : null;
            if (pid == null) continue;
            // preferir la oferta en stock; si no, la primera que aparezca
            SupplierOffer prev = offerByProduct.get(pid);
            if (prev == null || (Boolean.TRUE.equals(o.getInStock()) && !Boolean.TRUE.equals(prev.getInStock()))) {
                offerByProduct.put(pid, o);
            }
        }

        List<SupplierExcelFiller.OrderLine> orderLines = new ArrayList<>();
        if (sa != null) {
            for (AllocationResponse.AllocationLine l : sa.lines) {
                SupplierOffer o = l.productId != null ? offerByProduct.get(l.productId) : null;
                SupplierExcelFiller.OrderLine ol = new SupplierExcelFiller.OrderLine();
                ol.canonUpc = l.gtin != null ? GtinCanonicalizer.canonicalize(l.gtin).canonical14 : null;
                ol.rawDigits = o != null ? o.getGtinRaw() : l.gtin;
                ol.sku = o != null ? o.getSupplierSku() : null;
                ol.title = (o != null && o.getRawTitle() != null && !o.getRawTitle().isBlank())
                        ? o.getRawTitle()
                        : (((l.brand != null ? l.brand : "") + " " + (l.name != null ? l.name : "")).trim());
                ol.quantity = l.quantity;
                ol.gtin = l.gtin;
                ol.brand = l.brand;
                ol.name = l.name;
                orderLines.add(ol);
            }
        }

        try {
            SupplierExcelFiller.FillResult res = filler.fill(file.getBytes(), supplier.getName(), orderLines);
            FillReport report = res.report;

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
