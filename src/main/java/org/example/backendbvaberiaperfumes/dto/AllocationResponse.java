package org.example.backendbvaberiaperfumes.dto;

import java.util.ArrayList;
import java.util.List;

/** Resultado del motor de asignacion: que comprar a cada proveedor. Campos publicos -> JSON directo. */
public class AllocationResponse {
    public Long consolidadoId;
    public List<SupplierAllocation> suppliers = new ArrayList<>();
    public double baselineTotalUsd;     // todo al proveedor mas barato
    public double chosenTotalUsd;       // tras aplicar prioridad Zimaxx
    public double extraCostUsd;         // chosen - baseline = costo de forzar Zimaxx
    public boolean zimaxxPriorityEnabled;
    public boolean zimaxxMinReached;
    public double zimaxxGapUsd;         // cuanto falta para el minimo (0 si llego)
    public List<FillSuggestion> storeFillSuggestions = new ArrayList<>();
    public List<UnfulfillableItem> unfulfillable = new ArrayList<>();
    public List<String> notes = new ArrayList<>();

    // --- v2: plan persistido, analisis forzar/saltar y guardia de margen ---
    public Long planId;                                        // plan DRAFT creado (solo en /compute)
    public List<SupplierDecision> skipAnalysis = new ArrayList<>();
    public List<MarginWarning> marginWarnings = new ArrayList<>();
    public List<LostSale> lostSales = new ArrayList<>();
    public double penaltiesUsd;                                // relleno + ventas perdidas (contable)

    public static class SupplierDecision {
        public Long supplierId;
        public String name;
        public Double forceTotalUsd;   // costo total del mejor plan FORZANDO su minimo (null = inviable)
        public Double skipTotalUsd;    // costo total del mejor plan SALTANDOLO
        public String decision;        // FORZAR | SALTAR
    }

    public static class MarginWarning {
        public Long productId;
        public String name;
        public Long supplierId;
        public String supplierName;
        public double marginPen;       // margen unitario resultante (PEN)
        public double floorPen;        // piso configurado (min_margin_pen_per_unit)
    }

    public static class LostSale {
        public Long productId;
        public String name;
        public int quantity;
        public String reason;
    }

    public static class SupplierAllocation {
        public Long supplierId;
        public String name;
        public double minOrderUsd;
        public double subtotalUsd;
        public boolean reachedMin;
        public List<AllocationLine> lines = new ArrayList<>();
    }

    public static class AllocationLine {
        public Long productId;
        public String brand;
        public String name;
        public String gtin;          // UPC/GTIN canónico del producto (solo informativo)
        public Integer ml;
        public int quantity;
        public double unitCostUsd;
        public double subtotalUsd;
        public boolean movedToReachMin;   // se movio aqui solo para llegar al minimo de Zimaxx
        public double penaltyUsd;         // sobrecosto unitario vs el proveedor mas barato (0 si era el mas barato)

        // --- Trazabilidad de la decision (datos que el optimizador YA calculo) ---
        public Long chosenSupplierId;
        public Long cheapestSupplierId;
        public String reason;                            // motivo legible de la eleccion
        public List<AltPrice> alternatives = new ArrayList<>();  // precio en TODOS los proveedores con stock
    }

    /** Precio del mismo perfume en un proveedor (para explicar la decision). */
    public static class AltPrice {
        public Long supplierId;
        public String supplierName;
        public double unitCostUsd;
        public boolean chosen;    // es el proveedor elegido por el algoritmo
        public boolean cheapest;  // es el mas barato
    }

    public static class FillSuggestion {
        public Long productId;
        public String brand;
        public String name;
        public double costUsd;
    }

    public static class UnfulfillableItem {
        public Long productId;
        public String brand;
        public String name;
        public int quantity;
    }
}
