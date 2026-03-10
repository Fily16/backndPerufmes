package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.AdminRepository;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DataSeederService implements CommandLineRunner {

    private final ProductRepository productRepo;
    private final AdminRepository adminRepo;
    private final AppConfigRepository configRepo;
    private final ConsolidadoRepository consolidadoRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email}") private String adminEmail;
    @Value("${app.admin.password}") private String adminPassword;
    @Value("${app.config.courier-cost-per-kg}") private String courierCost;
    @Value("${app.config.exchange-rate}") private String exchangeRate;
    @Value("${app.config.target-margin}") private String targetMargin;
    @Value("${app.config.min-order-usd}") private String minOrder;
    @Value("${app.config.box-weight-g}") private String boxWeight;
    @Value("${app.config.perfumes-per-box}") private String perfumesPerBox;
    @Value("${app.config.yape-number}") private String yapeNumber;

    private final PricingService pricingService;

    public DataSeederService(ProductRepository productRepo, AdminRepository adminRepo,
                             AppConfigRepository configRepo, ConsolidadoRepository consolidadoRepo,
                             PasswordEncoder passwordEncoder, PricingService pricingService) {
        this.productRepo = productRepo;
        this.adminRepo = adminRepo;
        this.configRepo = configRepo;
        this.consolidadoRepo = consolidadoRepo;
        this.passwordEncoder = passwordEncoder;
        this.pricingService = pricingService;
    }

    @Override
    public void run(String... args) throws Exception {
        seedAdmin();
        seedConfig();
        seedProducts();
        calculateWholesalePrices();
        calculateRetailPrices();
        seedFirstConsolidado();
    }

    private void seedAdmin() {
        if (!adminRepo.existsByEmail(adminEmail)) {
            Admin admin = new Admin(adminEmail, passwordEncoder.encode(adminPassword), "AromaStudio Admin");
            adminRepo.save(admin);
            System.out.println("Admin created: " + adminEmail);
        }
    }

    private void seedConfig() {
        Map<String, String[]> configs = Map.ofEntries(
            Map.entry("courier_cost_per_kg", new String[]{courierCost, "Costo courier por kg (USD)"}),
            Map.entry("exchange_rate", new String[]{exchangeRate, "Tipo de cambio USD/PEN"}),
            Map.entry("target_margin", new String[]{targetMargin, "Margen objetivo (%)"}),
            Map.entry("min_order_usd", new String[]{minOrder, "Pedido minimo Oriental Aromas (USD)"}),
            Map.entry("box_weight_g", new String[]{boxWeight, "Peso caja empaque (g)"}),
            Map.entry("perfumes_per_box", new String[]{perfumesPerBox, "Perfumes por caja"}),
            Map.entry("yape_number", new String[]{yapeNumber, "Numero de Yape para pagos"}),
            Map.entry("wholesale_profit_per_unit", new String[]{"15", "Ganancia por unidad consolidado (S/)"}),
            Map.entry("miami_shipping_threshold", new String[]{"1000", "Umbral envio Miami sin cargo extra (USD)"}),
            Map.entry("miami_shipping_extra", new String[]{"35", "Costo extra Miami si subtotal < umbral (USD)"}),
            Map.entry("deposit_per_unit", new String[]{"20", "Monto de separacion por perfume (S/)"}),
            Map.entry("form_sale_api_key", new String[]{UUID.randomUUID().toString().replace("-", "").substring(0, 16), "API Key para Google Form (auto-generada)"})
        );

        configs.forEach((key, val) -> {
            if (configRepo.findByConfigKey(key).isEmpty()) {
                configRepo.save(new AppConfig(key, val[0], val[1]));
            }
        });
        System.out.println("Config seeded: " + configs.size() + " keys");
    }

    private void seedProducts() throws Exception {
        if (productRepo.count() > 0) {
            System.out.println("Products already seeded: " + productRepo.count());
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        InputStream is = new ClassPathResource("products-seed.json").getInputStream();
        List<Map<String, Object>> products = mapper.readValue(is, new TypeReference<>() {});

        for (Map<String, Object> p : products) {
            Product product = new Product();
            product.setSku((String) p.get("sku"));
            product.setBrand((String) p.get("brand"));
            product.setName((String) p.get("name"));
            product.setType((String) p.get("type"));
            product.setMl(((Number) p.get("ml")).intValue());
            product.setPriceUsd(((Number) p.get("priceUsd")).doubleValue());
            product.setWeightG(((Number) p.get("weightG")).intValue());
            product.setAvailable((Boolean) p.get("available"));

            // Set image URL from seed data
            if (p.get("imageUrl") != null) {
                product.setImageUrl((String) p.get("imageUrl"));
            }

            // Auto-set category from type
            String type = product.getType() != null ? product.getType().toLowerCase() : "";
            if (type.contains("women")) product.setCategory("women");
            else if (type.contains("men")) product.setCategory("men");
            else product.setCategory("unisex");

            productRepo.save(product);
        }

        System.out.println("Products seeded: " + products.size());
    }

    /**
     * Auto-calculate wholesalePricePen for products that don't have one yet.
     * Formula: landed cost in PEN + S/15 flat profit per unit.
     * Admin can override individual prices later from the admin panel.
     */
    private void calculateWholesalePrices() {
        double profitPerUnit = getConfigDouble("wholesale_profit_per_unit", 15.0);
        List<Product> products = productRepo.findAll();
        int updated = 0;
        for (Product p : products) {
            if (p.getWholesalePricePen() == null && p.getPriceUsd() != null && p.getWeightG() != null) {
                double landedCost = pricingService.calculateLandedCostUsd(p.getPriceUsd(), p.getWeightG());
                double costPen = pricingService.calculateCostPen(landedCost);
                // Flat profit: cost + S/15
                p.setWholesalePricePen(Math.round((costPen + profitPerUnit) * 100.0) / 100.0);
                productRepo.save(p);
                updated++;
            }
        }
        if (updated > 0) {
            System.out.println("Wholesale prices calculated for " + updated + " products (profit per unit: S/" + profitPerUnit + ")");
        }
    }

    /**
     * Auto-calculate retailPricePen for products that don't have one yet.
     * Formula: landed cost in PEN * 1.5 (50% margin over cost).
     */
    private void calculateRetailPrices() {
        List<Product> products = productRepo.findAll();
        int updated = 0;
        for (Product p : products) {
            if (p.getRetailPricePen() == null && p.getPriceUsd() != null && p.getWeightG() != null) {
                double landedCost = pricingService.calculateLandedCostUsd(p.getPriceUsd(), p.getWeightG());
                double costPen = pricingService.calculateCostPen(landedCost);
                // Retail price: cost * 1.5 (50% margin)
                p.setRetailPricePen(Math.round(costPen * 1.5 * 100.0) / 100.0);
                productRepo.save(p);
                updated++;
            }
        }
        if (updated > 0) {
            System.out.println("Retail prices calculated for " + updated + " products");
        }
    }

    private double getConfigDouble(String key, double defaultVal) {
        return configRepo.findByConfigKey(key)
                .map(c -> {
                    try { return Double.parseDouble(c.getConfigValue()); }
                    catch (NumberFormatException e) { return defaultVal; }
                }).orElse(defaultVal);
    }

    private void seedFirstConsolidado() {
        if (consolidadoRepo.count() == 0) {
            Consolidado c = new Consolidado();
            c.setStatus("ABIERTO");
            consolidadoRepo.save(c);
            System.out.println("First consolidado created (ABIERTO)");
        }
    }
}
