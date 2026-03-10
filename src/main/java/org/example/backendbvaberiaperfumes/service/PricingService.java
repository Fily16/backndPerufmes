package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.springframework.stereotype.Service;

/**
 * Replaces all Excel formulas from Configuración, Catálogo Maestro, and Calculadora sheets.
 * All pricing/cost calculations centralized here.
 */
@Service
public class PricingService {

    private final AppConfigRepository configRepo;

    public PricingService(AppConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    // --- Config getters ---
    public double getCourierCostPerKg() {
        return getConfigDouble("courier_cost_per_kg", 7.0);
    }

    public double getExchangeRate() {
        return getConfigDouble("exchange_rate", 3.4);
    }

    public double getTargetMargin() {
        return getConfigDouble("target_margin", 100.0);
    }

    public double getMinOrderUsd() {
        return getConfigDouble("min_order_usd", 350.0);
    }

    public double getBoxWeightG() {
        return getConfigDouble("box_weight_g", 300.0);
    }

    public int getPerfumesPerBox() {
        return getConfigInt("perfumes_per_box", 4);
    }

    public String getYapeNumber() {
        return configRepo.findByConfigKey("yape_number")
                .map(AppConfig::getConfigValue).orElse("903250695");
    }

    public double getDepositPerUnit() {
        return getConfigDouble("deposit_per_unit", 20.0);
    }

    // --- Excel formula: Catálogo Maestro col I (Costo Envío USD) ---
    // =IF(G4="","",(G4/1000)*Configuración!$B$5)
    public double calculateShippingCostUsd(int weightG) {
        return (weightG / 1000.0) * getCourierCostPerKg();
    }

    // --- Excel formula: Catálogo Maestro col J (Costo Puesto Perú USD) ---
    // =IF(F4="","",F4+I4)
    public double calculateLandedCostUsd(double priceUsd, int weightG) {
        return priceUsd + calculateShippingCostUsd(weightG);
    }

    // --- Excel formula: Pedido Actual col J (Costo PEN) ---
    // =IF(D4="","",D4*Configuración!$B$7)
    public double calculateCostPen(double landedCostUsd) {
        return landedCostUsd * getExchangeRate();
    }

    // --- Excel formula: Calculadora - Cajas necesarias ---
    // =ROUNDUP(B7/Configuración!$B$11,0)
    public int calculateBoxesNeeded(int totalUnits) {
        return (int) Math.ceil((double) totalUnits / getPerfumesPerBox());
    }

    // --- Excel formula: Calculadora - Peso total envío ---
    public int calculateTotalShippingWeightG(int perfumeWeightG, int totalUnits) {
        int boxes = calculateBoxesNeeded(totalUnits);
        return perfumeWeightG + (int) (boxes * getBoxWeightG());
    }

    // --- Excel formula: Resumen - Costo courier total ---
    // =IF(B12>0,MAX(B12,1)*Configuración!$B$5,0)
    public double calculateTotalCourierCostUsd(int totalWeightG, int totalUnits) {
        int boxes = calculateBoxesNeeded(totalUnits);
        double totalWithBoxes = totalWeightG + (boxes * getBoxWeightG());
        double kg = totalWithBoxes / 1000.0;
        return Math.max(kg, 1.0) * getCourierCostPerKg();
    }

    // --- Excel formula: Resumen - Envío extra Miami ---
    // =IF(B32="Sí",IF(B14<1000,35,0),0)
    public double calculateExtraMiamiShipping(double subtotalUsd) {
        return subtotalUsd < 1000 ? 35.0 : 0.0;
    }

    // --- Suggested prices (Calculadora sheet) ---
    public double suggestedPrice(double costPen, double marginPercent) {
        return costPen * (1 + marginPercent / 100.0);
    }

    // --- Consolidado summary calculations ---
    public double calculateTotalInvestmentPen(double subtotalProductsUsd, double courierCostUsd, double extraShippingUsd) {
        return (subtotalProductsUsd + courierCostUsd + extraShippingUsd) * getExchangeRate();
    }

    public double calculateProfitMargin(double profitPen, double investmentPen) {
        return investmentPen > 0 ? (profitPen / investmentPen) * 100 : 0;
    }

    // --- Helpers ---
    private double getConfigDouble(String key, double defaultVal) {
        return configRepo.findByConfigKey(key)
                .map(c -> {
                    try { return Double.parseDouble(c.getConfigValue()); }
                    catch (NumberFormatException e) { return defaultVal; }
                }).orElse(defaultVal);
    }

    private int getConfigInt(String key, int defaultVal) {
        return configRepo.findByConfigKey(key)
                .map(c -> {
                    try { return Integer.parseInt(c.getConfigValue()); }
                    catch (NumberFormatException e) { return defaultVal; }
                }).orElse(defaultVal);
    }
}
