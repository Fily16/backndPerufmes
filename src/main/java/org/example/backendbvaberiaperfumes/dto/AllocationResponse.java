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
        public Integer ml;
        public int quantity;
        public double unitCostUsd;
        public double subtotalUsd;
        public boolean movedToReachMin;   // se movio aqui solo para llegar al minimo de Zimaxx
        public double penaltyUsd;         // sobrecosto unitario vs el proveedor mas barato (0 si era el mas barato)
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
