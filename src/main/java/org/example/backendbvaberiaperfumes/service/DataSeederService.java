package org.example.backendbvaberiaperfumes.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.AdminRepository;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierConstraintRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
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
    private final SupplierRepository supplierRepo;
    private final SupplierOfferRepository offerRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.migrate.h2:false}") private boolean migrating;
    @Value("${app.admin.email}") private String adminEmail;
    @Value("${app.admin.password}") private String adminPassword;
    /** Admins extra por env: EXTRA_ADMINS=correo:clave:Nombre;correo2:clave2:Nombre2 */
    @Value("${app.extra-admins:}") private String extraAdmins;
    @Value("${app.config.courier-cost-per-kg}") private String courierCost;
    @Value("${app.config.exchange-rate}") private String exchangeRate;
    @Value("${app.config.target-margin}") private String targetMargin;
    @Value("${app.config.min-order-usd}") private String minOrder;
    @Value("${app.config.box-weight-g}") private String boxWeight;
    @Value("${app.config.perfumes-per-box}") private String perfumesPerBox;
    @Value("${app.config.yape-number}") private String yapeNumber;
    @Value("${app.config.repack-cost-per-box}") private String repackCost;
    @Value("${app.config.zimaxx-priority}") private String zimaxxPriority;

    /** Banners por defecto del home (sin imagen: el front muestra un fondo elegante hasta que el admin pegue la URL).
     *  Texto con escapes \\uXXXX para ser independiente del encoding del compilador (fuente ASCII). */
    private static final String DEFAULT_BANNERS_JSON =
            "[{\"imageUrl\":\"\",\"title\":\"Perfumes árabes al por mayor\",\"subtitle\":\"Importación directa • Precios de mayorista\",\"ctaText\":\"Ver catálogo\",\"linkType\":\"category\",\"linkValue\":\"all\"}," +
            "{\"imageUrl\":\"\",\"title\":\"Los más pedidos\",\"subtitle\":\"Khamrah, Yara, Club de Nuit y más\",\"ctaText\":\"Comprar ahora\",\"linkType\":\"search\",\"linkValue\":\"khamrah\"}]";

    private final PricingService pricingService;
    private final DuplicateScanService duplicateScanService;
    private final SupplierConstraintRepository constraintRepo;

    public DataSeederService(ProductRepository productRepo, AdminRepository adminRepo,
                             AppConfigRepository configRepo, ConsolidadoRepository consolidadoRepo,
                             SupplierRepository supplierRepo, SupplierOfferRepository offerRepo,
                             PasswordEncoder passwordEncoder, PricingService pricingService,
                             DuplicateScanService duplicateScanService,
                             SupplierConstraintRepository constraintRepo) {
        this.productRepo = productRepo;
        this.adminRepo = adminRepo;
        this.configRepo = configRepo;
        this.consolidadoRepo = consolidadoRepo;
        this.supplierRepo = supplierRepo;
        this.offerRepo = offerRepo;
        this.passwordEncoder = passwordEncoder;
        this.pricingService = pricingService;
        this.duplicateScanService = duplicateScanService;
        this.constraintRepo = constraintRepo;
    }

    @Override
    public void run(String... args) throws Exception {
        if (migrating) {
            System.out.println("[SEED] Migración H2->destino activa: se omite el seed/normalización.");
            return;
        }
        seedAdmin();
        seedSuppliers();
        seedConfig();
        forcePricingConfig();
        seedProducts();
        calculateWholesalePrices();
        calculateRetailPrices();
        normalizePricesOnce();
        renormalizeGtinsOnce();
        resyncPriceUsdOnce();
        scanDuplicatesOnce();
        migrateSupplierConstraintsOnce();
        seedFirstConsolidado();
    }

    /**
     * Migra UNA sola vez los minimos legacy (Supplier.minOrderUsd) a la tabla de
     * restricciones (supplier_constraints), que es lo que lee el optimizador de compra.
     * Ademas siembra el minimo de 48 UNIDADES de FragranceSense si el proveedor existe.
     */
    private void migrateSupplierConstraintsOnce() {
        if (configRepo.findByConfigKey("supplier_constraints_v1").isPresent()) return;
        int created = 0;
        for (Supplier s : supplierRepo.findAll()) {
            if (s.getMinOrderUsd() != null && s.getMinOrderUsd() > 0
                    && !constraintRepo.existsBySupplier_IdAndType(s.getId(), "MIN_ORDER_USD")) {
                constraintRepo.save(new SupplierConstraint(s, "MIN_ORDER_USD", s.getMinOrderUsd()));
                created++;
            }
            // FragranceSense: minimo de 48 unidades sin importar cuales.
            if (s.getName() != null && s.getName().toLowerCase().contains("fragrancesense")
                    && !constraintRepo.existsBySupplier_IdAndType(s.getId(), "MIN_UNITS")) {
                constraintRepo.save(new SupplierConstraint(s, "MIN_UNITS", 48.0));
                created++;
            }
        }
        forceConfig("supplier_constraints_v1", "true", "Minimos de proveedor migrados a supplier_constraints (corrida unica)");
        System.out.println("[CONSTRAINTS] Restricciones migradas/creadas: " + created);
    }

    /**
     * Re-sincroniza UNA sola vez el legacy Product.priceUsd con el costo real de las
     * ofertas de proveedor (antes quedaba congelado al valor de la primera importacion,
     * y la ganancia del consolidado / recalculo por config leian ese valor obsoleto).
     * Productos sin ofertas (seed puro) conservan su priceUsd.
     */
    private void resyncPriceUsdOnce() {
        if (configRepo.findByConfigKey("price_usd_resync_v1").isPresent()) return;
        int updated = 0;
        Map<Long, Double> cheapestByProduct = new java.util.HashMap<>();
        for (SupplierOffer o : offerRepo.findAll()) {
            if (o.getCostUsd() == null || !Boolean.TRUE.equals(o.getInStock())) continue;
            if (o.getSupplier() == null || !Boolean.TRUE.equals(o.getSupplier().getActive())) continue;
            cheapestByProduct.merge(o.getProduct().getId(), o.getCostUsd(), Math::min);
        }
        for (Map.Entry<Long, Double> e : cheapestByProduct.entrySet()) {
            Product p = productRepo.findById(e.getKey()).orElse(null);
            if (p == null) continue;
            if (p.getPriceUsd() == null || Math.abs(p.getPriceUsd() - e.getValue()) > 0.001) {
                p.setPriceUsd(e.getValue());
                productRepo.save(p);
                updated++;
            }
        }
        forceConfig("price_usd_resync_v1", "true", "priceUsd re-sincronizado con las ofertas de proveedor (corrida unica)");
        System.out.println("[COSTO] priceUsd re-sincronizado en " + updated + " productos");
    }

    /**
     * Puebla UNA sola vez la cola de revision con los duplicados historicos del catalogo
     * (seed sin GTIN vs importados con GTIN). No fusiona nada: el admin decide cada caso
     * desde el panel. Se puede relanzar a demanda con POST /api/admin/duplicates/scan.
     */
    private void scanDuplicatesOnce() {
        if (configRepo.findByConfigKey("dedup_scan_v1").isPresent()) return;
        try {
            int created = duplicateScanService.scan();
            System.out.println("[DEDUP] Candidatos de fusion detectados para revision: " + created);
        } catch (Exception e) {
            System.err.println("[DEDUP] Escaneo inicial fallo (se puede relanzar desde el admin): " + e.getMessage());
        }
        forceConfig("dedup_scan_v1", "true", "Escaneo inicial de duplicados ejecutado (corrida unica)");
    }

    /**
     * Re-canonicaliza UNA sola vez todos los GTIN guardados con el validador de checksum
     * (GtinCanonicalizer). Los codigos con checksum invalido (typos del proveedor) quedan
     * en cuarentena: gtin=null + status CHECKSUM_FAIL; la identidad pasa a resolverse por
     * matching de nombre (L2). NO fusiona nada: eso lo decide el admin desde la cola de revision.
     */
    private void renormalizeGtinsOnce() {
        if (configRepo.findByConfigKey("gtin_canonical_v1").isPresent()) return;
        int offersQuarantined = 0, productsQuarantined = 0;
        for (SupplierOffer o : offerRepo.findAll()) {
            String source = o.getGtin() != null ? o.getGtin() : o.getGtinRaw();
            if (source == null) continue;
            GtinCanonicalizer.GtinResult r = GtinCanonicalizer.canonicalize(source);
            o.setGtinRaw(r.rawDigits);
            o.setGtinStatus(r.status.name());
            if (r.ok()) {
                o.setGtin(r.canonical14);
            } else {
                o.setGtin(null);
                offersQuarantined++;
            }
            offerRepo.save(o);
        }
        for (Product p : productRepo.findAll()) {
            if (p.getGtin() == null) continue;
            GtinCanonicalizer.GtinResult r = GtinCanonicalizer.canonicalize(p.getGtin());
            if (r.ok()) {
                if (!r.canonical14.equals(p.getGtin())) {
                    p.setGtin(r.canonical14);
                    productRepo.save(p);
                }
            } else {
                p.setGtin(null);
                p.setGtinConflict(true);
                productRepo.save(p);
                productsQuarantined++;
            }
        }
        forceConfig("gtin_canonical_v1", "true", "GTINs re-validados con checksum GS1 (corrida unica)");
        System.out.println("[GTIN] Cuarentena por checksum invalido: " + offersQuarantined
                + " ofertas, " + productsQuarantined + " productos");
    }

    /** Fuerza los parámetros fijos de precio. Courier=9 (editable en Ajustes, pero se corrige aquí). */
    private void forcePricingConfig() {
        forceConfig("wholesale_profit_per_unit", "20", "Ganancia fija por unidad al público (S/)");
        forceConfig("stock_extra_pen", "35", "Premio por venta inmediata desde stock (S/)");
        forceConfig("courier_cost_per_kg", "9", "Costo courier por kg (USD)");
    }

    private void forceConfig(String key, String value, String desc) {
        AppConfig c = configRepo.findByConfigKey(key).orElseGet(() -> {
            AppConfig n = new AppConfig();
            n.setConfigKey(key);
            n.setDescription(desc);
            return n;
        });
        c.setConfigValue(value);
        configRepo.save(c);
    }

    /**
     * Normaliza UNA sola vez todos los precios al estándar: público = costo(envío+caja)+S/20,
     * stock = costo+S/35, redondeado hacia arriba. Se guarda un flag para no volver a correr
     * (así se respetan los precios que el admin cambie manualmente después).
     */
    private void normalizePricesOnce() {
        if (configRepo.findByConfigKey("prices_normalized_v3").isPresent()) return;
        int n = 0;
        for (Product p : productRepo.findAll()) {
            if (p.getPriceUsd() == null || p.getWeightG() == null) continue;
            p.setWholesalePricePen(pricingService.suggestedPublicPricePen(p.getPriceUsd(), p.getWeightG()));
            if (p.getStockPricePen() != null) {
                p.setStockPricePen(pricingService.suggestedStockPricePen(p.getPriceUsd(), p.getWeightG()));
            }
            productRepo.save(p);
            n++;
        }
        forceConfig("prices_normalized_v3", "true", "Precios normalizados a costo+20 con courier=9 (corrida única)");
        System.out.println("Precios normalizados (costo+20, redondeo arriba): " + n + " productos");
    }

    private void seedSuppliers() {
        // Bootstrap unico: solo siembra cuando NO existe ningun proveedor. Asi un proveedor
        // eliminado permanentemente no reaparece en el proximo arranque.
        if (supplierRepo.count() > 0) return;
        if (supplierRepo.findByName("Zimaxx").isEmpty()) {
            Supplier z = supplierRepo.save(new Supplier("Zimaxx", 2000.0, true));
            // La constraint va junto al seed: la migracion one-time (supplier_constraints_v1)
            // pudo haber corrido antes y no se repite si el proveedor se borro y re-sembro.
            constraintRepo.save(new SupplierConstraint(z, "MIN_ORDER_USD", 2000.0));
            System.out.println("Supplier creado: Zimaxx (min $2000, prioridad)");
        }
        if (supplierRepo.findByName("Magnet").isEmpty()) {
            supplierRepo.save(new Supplier("Magnet", 0.0, false));
            System.out.println("Supplier creado: Magnet (sin minimo)");
        }
    }

    private void seedAdmin() {
        if (!adminRepo.existsByEmail(adminEmail)) {
            Admin admin = new Admin(adminEmail, passwordEncoder.encode(adminPassword), "Administrador");
            adminRepo.save(admin);
            System.out.println("Admin created: " + adminEmail);
        }
        // Segundo vendedor (compañero) para el ERP multiusuario
        String socioEmail = "socio@aromastudio.pe";
        if (!adminRepo.existsByEmail(socioEmail)) {
            adminRepo.save(new Admin(socioEmail, passwordEncoder.encode("socio123"), "Socio"));
            System.out.println("Admin created: " + socioEmail + " (vendedor)");
        }

        // Admins extra desde la variable de entorno EXTRA_ADMINS
        // Formato: correo:clave:Nombre;correo2:clave2:Nombre2  (el Nombre es opcional)
        if (extraAdmins != null && !extraAdmins.isBlank()) {
            for (String entry : extraAdmins.split(";")) {
                String[] p = entry.trim().split(":", 3);
                if (p.length < 2) continue;
                String email = p[0].trim(), pass = p[1].trim();
                String name = p.length >= 3 && !p[2].trim().isEmpty() ? p[2].trim() : "Administrador";
                if (email.isEmpty() || pass.isEmpty()) continue;
                if (!adminRepo.existsByEmail(email)) {
                    adminRepo.save(new Admin(email, passwordEncoder.encode(pass), name));
                    System.out.println("Admin extra creado: " + email);
                }
            }
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
            Map.entry("wholesale_profit_per_unit", new String[]{"20", "Ganancia por unidad consolidado (S/)"}),
            Map.entry("miami_shipping_threshold", new String[]{"1000", "Umbral envio Miami sin cargo extra (USD)"}),
            Map.entry("miami_shipping_extra", new String[]{"35", "Costo extra Miami si subtotal < umbral (USD)"}),
            Map.entry("deposit_per_unit", new String[]{"20", "Monto de separacion por perfume (S/)"}),
            Map.entry("repack_cost_per_box", new String[]{repackCost, "Costo de reempaque por caja de 4 perfumes (USD)"}),
            Map.entry("zimaxx_priority_enabled", new String[]{zimaxxPriority, "Forzar llegar al minimo de Zimaxx ($2000) en la asignacion de compra"}),
            Map.entry("pricing_basis", new String[]{"CHEAPEST", "Base del precio publicado: CHEAPEST | PRIORITY | WORST_PLAUSIBLE"}),
            Map.entry("min_margin_pen_per_unit", new String[]{"8", "Margen minimo por unidad (S/): bajo esto la asignacion de compra avisa/bloquea"}),
            Map.entry("plausible_band_pct", new String[]{"12", "Banda % sobre la oferta mas barata para WORST_PLAUSIBLE"}),
            Map.entry("match_review_jaccard", new String[]{"0.6", "Similitud minima (0-1) para proponer un posible duplicado a revision"}),
            Map.entry("storefill_penalty_pct", new String[]{"15", "Penalidad contable (%) del relleno de tienda para llegar a un minimo"}),
            Map.entry("lost_sale_penalty_pen", new String[]{"30", "Penalidad (S/ por unidad) por perder una venta al saltar un proveedor"}),
            Map.entry("min_plausible_cost_usd", new String[]{"4", "Costo minimo plausible (USD): bajo esto la fila importada es sospechosa"}),
            Map.entry("max_plausible_cost_usd", new String[]{"400", "Costo maximo plausible (USD): sobre esto la fila importada es sospechosa"}),
            Map.entry("form_sale_api_key", new String[]{UUID.randomUUID().toString().replace("-", "").substring(0, 16), "API Key para Google Form (auto-generada)"}),
            Map.entry("home_banners", new String[]{DEFAULT_BANNERS_JSON, "Banners del home (JSON): imageUrl,title,subtitle,ctaText,linkType(product|brand|category|search|url),linkValue"}),
            Map.entry("home_promos", new String[]{"[]", "Tiles de promociones del home (JSON, misma estructura que home_banners). Vacio = oculto."})
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
