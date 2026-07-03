package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Decide que comprar a cada proveedor para un consolidado.
 * Base: cada producto a su proveedor mas barato (por costo unitario USD).
 * Prioridad Zimaxx: si esta activa y Zimaxx no llega a su minimo, mueve productos
 * Magnet->Zimaxx por MENOR penalidad hasta cruzar el minimo (victorias gratis primero),
 * y si aun falta, sugiere relleno con stock de tienda. Siempre reporta el sobrecosto.
 */
@Service
public class AllocationService {

    private final ConsolidadoService consolidadoService;
    private final SupplierRepository supplierRepo;
    private final SupplierOfferRepository offerRepo;
    private final ProductRepository productRepo;
    private final AppConfigRepository configRepo;

    public AllocationService(ConsolidadoService consolidadoService, SupplierRepository supplierRepo,
                             SupplierOfferRepository offerRepo, ProductRepository productRepo,
                             AppConfigRepository configRepo) {
        this.consolidadoService = consolidadoService;
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.productRepo = productRepo;
        this.configRepo = configRepo;
    }

    private static class Assign {
        Product product;
        int qty;
        Long supplierId;
        double unitCost;
        double baselineUnitCost;
        Double zimaxxUnitCost;     // costo en Zimaxx si tiene oferta, sino null
        double penalty = 0;
        boolean moved = false;
    }

    public AllocationResponse computeAllocation(Long consolidadoId) {
        AllocationResponse resp = new AllocationResponse();
        resp.consolidadoId = consolidadoId;

        Map<Long, Integer> demand = consolidadoService.getActiveDemandByProduct(consolidadoId);
        List<Supplier> suppliers = supplierRepo.findAll();
        Map<Long, Supplier> supById = suppliers.stream().collect(Collectors.toMap(Supplier::getId, s -> s));
        Supplier zimaxx = suppliers.stream()
                .filter(s -> Boolean.TRUE.equals(s.getPriorityToReachMin()))
                .findFirst().orElse(null);
        boolean priorityEnabled = "true".equalsIgnoreCase(configValue("zimaxx_priority_enabled", "true"));
        resp.zimaxxPriorityEnabled = priorityEnabled && zimaxx != null;

        // 1. baseline: cada producto a su proveedor mas barato
        Map<Long, Assign> assigns = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : demand.entrySet()) {
            Long pid = e.getKey();
            int qty = e.getValue();
            List<SupplierOffer> offers = offerRepo.findByProduct_IdAndInStockTrue(pid).stream()
                    .filter(o -> o.getCostUsd() != null)
                    .collect(Collectors.toList());
            if (offers.isEmpty()) {
                Product p = productRepo.findById(pid).orElse(null);
                AllocationResponse.UnfulfillableItem u = new AllocationResponse.UnfulfillableItem();
                u.productId = pid;
                u.brand = p != null ? p.getBrand() : null;
                u.name = p != null ? p.getName() : ("#" + pid);
                u.quantity = qty;
                resp.unfulfillable.add(u);
                continue;
            }
            SupplierOffer cheapest = offers.stream()
                    .min(Comparator.comparingDouble(SupplierOffer::getCostUsd)).get();
            Assign a = new Assign();
            a.product = cheapest.getProduct();
            a.qty = qty;
            a.supplierId = cheapest.getSupplierId();
            a.unitCost = cheapest.getCostUsd();
            a.baselineUnitCost = cheapest.getCostUsd();
            if (zimaxx != null) {
                for (SupplierOffer o : offers) {
                    if (zimaxx.getId().equals(o.getSupplierId())) a.zimaxxUnitCost = o.getCostUsd();
                }
            }
            assigns.put(pid, a);
        }

        double baseline = assigns.values().stream().mapToDouble(a -> a.baselineUnitCost * a.qty).sum();
        resp.baselineTotalUsd = round(baseline);

        // 2. prioridad Zimaxx
        if (resp.zimaxxPriorityEnabled) {
            double zsub = zimaxxSubtotal(assigns, zimaxx.getId());
            if (zsub < zimaxx.getMinOrderUsd()) {
                List<Assign> candidates = assigns.values().stream()
                        .filter(a -> !zimaxx.getId().equals(a.supplierId) && a.zimaxxUnitCost != null)
                        .sorted(Comparator.comparingDouble(a -> (a.zimaxxUnitCost - a.baselineUnitCost)))
                        .collect(Collectors.toList());
                for (Assign a : candidates) {
                    if (zsub >= zimaxx.getMinOrderUsd()) break;
                    a.supplierId = zimaxx.getId();
                    a.unitCost = a.zimaxxUnitCost;
                    a.penalty = a.zimaxxUnitCost - a.baselineUnitCost;
                    a.moved = true;
                    zsub += a.unitCost * a.qty;
                }
                resp.zimaxxMinReached = zsub >= zimaxx.getMinOrderUsd();
                if (!resp.zimaxxMinReached) {
                    resp.zimaxxGapUsd = round(zimaxx.getMinOrderUsd() - zsub);
                    buildStoreFill(resp, zimaxx, demand.keySet());
                }
            } else {
                resp.zimaxxMinReached = true;
            }
        }

        // 3. agrupar por proveedor
        Map<Long, AllocationResponse.SupplierAllocation> groups = new LinkedHashMap<>();
        double chosen = 0;
        for (Assign a : assigns.values()) {
            AllocationResponse.SupplierAllocation g = groups.computeIfAbsent(a.supplierId, sid -> {
                Supplier s = supById.get(sid);
                AllocationResponse.SupplierAllocation ga = new AllocationResponse.SupplierAllocation();
                ga.supplierId = sid;
                ga.name = s != null ? s.getName() : ("#" + sid);
                ga.minOrderUsd = s != null ? s.getMinOrderUsd() : 0;
                return ga;
            });
            AllocationResponse.AllocationLine line = new AllocationResponse.AllocationLine();
            line.productId = a.product.getId();
            line.brand = a.product.getBrand();
            line.name = a.product.getName();
            line.ml = a.product.getMl();
            line.quantity = a.qty;
            line.unitCostUsd = round(a.unitCost);
            line.subtotalUsd = round(a.unitCost * a.qty);
            line.movedToReachMin = a.moved;
            line.penaltyUsd = round(a.penalty);
            g.lines.add(line);
            g.subtotalUsd += a.unitCost * a.qty;
            chosen += a.unitCost * a.qty;
        }
        for (AllocationResponse.SupplierAllocation g : groups.values()) {
            g.subtotalUsd = round(g.subtotalUsd);
            g.reachedMin = g.subtotalUsd >= g.minOrderUsd;
            g.lines.sort(Comparator.comparingDouble((AllocationResponse.AllocationLine l) -> l.subtotalUsd).reversed());
            resp.suppliers.add(g);
        }
        resp.chosenTotalUsd = round(chosen);
        resp.extraCostUsd = round(chosen - baseline);

        // 4. notas
        if (resp.zimaxxPriorityEnabled && !resp.zimaxxMinReached && zimaxx != null) {
            resp.notes.add("Zimaxx no llega al minimo de $" + fmt(zimaxx.getMinOrderUsd())
                    + ". Faltan $" + fmt(resp.zimaxxGapUsd)
                    + ". Rellena con stock de tienda (ver sugerencias) o desactiva la prioridad esta vuelta.");
        }
        if (resp.extraCostUsd > 0) {
            resp.notes.add("Llegar al minimo de Zimaxx cuesta $" + fmt(resp.extraCostUsd)
                    + " extra vs comprar todo al mas barato.");
        }
        if (!resp.unfulfillable.isEmpty()) {
            resp.notes.add(resp.unfulfillable.size() + " producto(s) sin stock en ningun proveedor.");
        }
        if (demand.isEmpty()) {
            resp.notes.add("No hay demanda de clientes activa en este consolidado.");
        }
        return resp;
    }

    private double zimaxxSubtotal(Map<Long, Assign> assigns, Long zimaxxId) {
        return assigns.values().stream()
                .filter(a -> zimaxxId.equals(a.supplierId))
                .mapToDouble(a -> a.unitCost * a.qty).sum();
    }

    private void buildStoreFill(AllocationResponse resp, Supplier zimaxx, Set<Long> demandProductIds) {
        List<SupplierOffer> zOffers = offerRepo.findBySupplier_Id(zimaxx.getId()).stream()
                .filter(o -> Boolean.TRUE.equals(o.getInStock()) && o.getCostUsd() != null)
                .filter(o -> !demandProductIds.contains(o.getProduct().getId()))
                .sorted(Comparator.comparingDouble(SupplierOffer::getCostUsd).reversed())
                .limit(15)
                .collect(Collectors.toList());
        for (SupplierOffer o : zOffers) {
            AllocationResponse.FillSuggestion f = new AllocationResponse.FillSuggestion();
            f.productId = o.getProduct().getId();
            f.brand = o.getProduct().getBrand();
            f.name = o.getProduct().getName();
            f.costUsd = round(o.getCostUsd());
            resp.storeFillSuggestions.add(f);
        }
    }

    private String configValue(String key, String def) {
        return configRepo.findByConfigKey(key).map(AppConfig::getConfigValue).orElse(def);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
