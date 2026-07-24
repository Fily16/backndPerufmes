package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.dto.FillReport;
import org.example.backendbvaberiaperfumes.dto.SingleSupplierPlan;
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
                                       @RequestParam("file") MultipartFile file,
                                       @RequestParam(defaultValue = "false") boolean consolidate) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Archivo vacío o no enviado."));
        }
        Supplier supplier = supplierRepo.findById(supplierId).orElse(null);
        if (supplier == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Proveedor no encontrado."));
        }

        // Filas a pedir a ESTE proveedor. Normal = lo que el motor le asignó. Consolidado =
        // TODO lo que se puede comprar en él (incluye reasignados desde otros proveedores).
        List<Demand> demand = new ArrayList<>();
        if (consolidate) {
            SingleSupplierPlan plan = allocationService.consolidateToSupplier(consolidadoId, supplierId);
            for (SingleSupplierPlan.BuyLine b : plan.buy) {
                demand.add(new Demand(b.productId, b.gtin, b.brand, b.name, b.quantity));
            }
        } else {
            AllocationResponse alloc = allocationService.computeAllocation(consolidadoId);
            AllocationResponse.SupplierAllocation sa = alloc.suppliers.stream()
                    .filter(s -> supplierId.equals(s.supplierId)).findFirst().orElse(null);
            if (sa != null) {
                for (AllocationResponse.AllocationLine l : sa.lines) {
                    demand.add(new Demand(l.productId, l.gtin, l.brand, l.name, l.quantity));
                }
            }
        }

        List<SupplierExcelFiller.OrderLine> orderLines = buildOrderLines(demand, supplierId);

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

    /** Fila a pedir a un proveedor: identidad del producto + cantidad (fuente normal o consolidada). */
    private record Demand(Long productId, String gtin, String brand, String name, int quantity) {}

    /**
     * Construye las OrderLine con las claves de ubicación EXACTAS del proveedor (dígitos crudos del
     * código, SKU y título tal como se importaron en {@code SupplierOffer}) para el match robusto.
     */
    private List<SupplierExcelFiller.OrderLine> buildOrderLines(List<Demand> demand, Long supplierId) {
        Map<Long, SupplierOffer> offerByProduct = new HashMap<>();
        for (SupplierOffer o : offerRepo.findBySupplier_Id(supplierId)) {
            Long pid = o.getProduct() != null ? o.getProduct().getId() : null;
            if (pid == null) continue;
            SupplierOffer prev = offerByProduct.get(pid); // preferir la oferta en stock
            if (prev == null || (Boolean.TRUE.equals(o.getInStock()) && !Boolean.TRUE.equals(prev.getInStock()))) {
                offerByProduct.put(pid, o);
            }
        }

        List<SupplierExcelFiller.OrderLine> orderLines = new ArrayList<>();
        for (Demand d : demand) {
            SupplierOffer o = d.productId() != null ? offerByProduct.get(d.productId()) : null;
            SupplierExcelFiller.OrderLine ol = new SupplierExcelFiller.OrderLine();
            ol.canonUpc = d.gtin() != null ? GtinCanonicalizer.canonicalize(d.gtin()).canonical14 : null;
            ol.rawDigits = o != null ? o.getGtinRaw() : d.gtin();
            ol.sku = o != null ? o.getSupplierSku() : null;
            ol.title = (o != null && o.getRawTitle() != null && !o.getRawTitle().isBlank())
                    ? o.getRawTitle()
                    : (((d.brand() != null ? d.brand() : "") + " " + (d.name() != null ? d.name() : "")).trim());
            ol.quantity = d.quantity();
            ol.gtin = d.gtin();
            ol.brand = d.brand();
            ol.name = d.name();
            orderLines.add(ol);
        }
        return orderLines;
    }
}
