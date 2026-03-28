package org.example.backendbvaberiaperfumes.dto;

import java.util.ArrayList;
import java.util.List;

public class BreakdownSection {
    private int orderCount;
    private int totalUnits;
    private List<ProductCount> productCounts = new ArrayList<>();
    private int weightPerfumesG;
    private int boxesNeeded;
    private int weightBoxesG;
    private int totalWeightG;
    private double subtotalProductsUsd;
    private double courierCostUsd;
    private double extraMiamiUsd;
    private double totalCostUsd;
    private double exchangeRate;
    private double totalInvestmentPen;
    private Double totalRevenuePen;
    private Double profitPen;
    private Double marginPercent;

    public int getOrderCount() { return orderCount; }
    public void setOrderCount(int orderCount) { this.orderCount = orderCount; }
    public int getTotalUnits() { return totalUnits; }
    public void setTotalUnits(int totalUnits) { this.totalUnits = totalUnits; }
    public int getWeightPerfumesG() { return weightPerfumesG; }
    public void setWeightPerfumesG(int weightPerfumesG) { this.weightPerfumesG = weightPerfumesG; }
    public int getBoxesNeeded() { return boxesNeeded; }
    public void setBoxesNeeded(int boxesNeeded) { this.boxesNeeded = boxesNeeded; }
    public int getWeightBoxesG() { return weightBoxesG; }
    public void setWeightBoxesG(int weightBoxesG) { this.weightBoxesG = weightBoxesG; }
    public int getTotalWeightG() { return totalWeightG; }
    public void setTotalWeightG(int totalWeightG) { this.totalWeightG = totalWeightG; }
    public double getSubtotalProductsUsd() { return subtotalProductsUsd; }
    public void setSubtotalProductsUsd(double subtotalProductsUsd) { this.subtotalProductsUsd = subtotalProductsUsd; }
    public double getCourierCostUsd() { return courierCostUsd; }
    public void setCourierCostUsd(double courierCostUsd) { this.courierCostUsd = courierCostUsd; }
    public double getExtraMiamiUsd() { return extraMiamiUsd; }
    public void setExtraMiamiUsd(double extraMiamiUsd) { this.extraMiamiUsd = extraMiamiUsd; }
    public double getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(double totalCostUsd) { this.totalCostUsd = totalCostUsd; }
    public double getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(double exchangeRate) { this.exchangeRate = exchangeRate; }
    public double getTotalInvestmentPen() { return totalInvestmentPen; }
    public void setTotalInvestmentPen(double totalInvestmentPen) { this.totalInvestmentPen = totalInvestmentPen; }
    public Double getTotalRevenuePen() { return totalRevenuePen; }
    public void setTotalRevenuePen(Double totalRevenuePen) { this.totalRevenuePen = totalRevenuePen; }
    public Double getProfitPen() { return profitPen; }
    public void setProfitPen(Double profitPen) { this.profitPen = profitPen; }
    public Double getMarginPercent() { return marginPercent; }
    public void setMarginPercent(Double marginPercent) { this.marginPercent = marginPercent; }
    public List<ProductCount> getProductCounts() { return productCounts; }
    public void setProductCounts(List<ProductCount> productCounts) { this.productCounts = productCounts; }
}
