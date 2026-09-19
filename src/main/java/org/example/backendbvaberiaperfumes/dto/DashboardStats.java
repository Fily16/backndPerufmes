package org.example.backendbvaberiaperfumes.dto;

public class DashboardStats {
    private long totalProducts;
    private long activeConsolidados;
    private long pendingOrders;
    private long verifiedOrders;
    private double totalRevenuePen;
    private double totalProfitPen;
    private int retailStock;
    private int retailSalesCount;
    private double retailRevenuePen;
    private double retailProfitPen;
    // NSO (perfumes vigentes por estado) y si el filtro de la tienda esta activo
    private int nsoConNso;
    private int nsoPending;
    private int nsoMarca;
    private int nsoSin;
    private boolean nsoGateEffective;

    public long getTotalProducts() { return totalProducts; }
    public void setTotalProducts(long v) { this.totalProducts = v; }
    public long getActiveConsolidados() { return activeConsolidados; }
    public void setActiveConsolidados(long v) { this.activeConsolidados = v; }
    public long getPendingOrders() { return pendingOrders; }
    public void setPendingOrders(long v) { this.pendingOrders = v; }
    public long getVerifiedOrders() { return verifiedOrders; }
    public void setVerifiedOrders(long v) { this.verifiedOrders = v; }
    public double getTotalRevenuePen() { return totalRevenuePen; }
    public void setTotalRevenuePen(double v) { this.totalRevenuePen = v; }
    public double getTotalProfitPen() { return totalProfitPen; }
    public void setTotalProfitPen(double v) { this.totalProfitPen = v; }
    public int getRetailStock() { return retailStock; }
    public void setRetailStock(int v) { this.retailStock = v; }
    public int getRetailSalesCount() { return retailSalesCount; }
    public void setRetailSalesCount(int v) { this.retailSalesCount = v; }
    public double getRetailRevenuePen() { return retailRevenuePen; }
    public void setRetailRevenuePen(double v) { this.retailRevenuePen = v; }
    public double getRetailProfitPen() { return retailProfitPen; }
    public void setRetailProfitPen(double v) { this.retailProfitPen = v; }
    public int getNsoConNso() { return nsoConNso; }
    public void setNsoConNso(int v) { this.nsoConNso = v; }
    public int getNsoPending() { return nsoPending; }
    public void setNsoPending(int v) { this.nsoPending = v; }
    public int getNsoMarca() { return nsoMarca; }
    public void setNsoMarca(int v) { this.nsoMarca = v; }
    public int getNsoSin() { return nsoSin; }
    public void setNsoSin(int v) { this.nsoSin = v; }
    public boolean isNsoGateEffective() { return nsoGateEffective; }
    public void setNsoGateEffective(boolean v) { this.nsoGateEffective = v; }
}
