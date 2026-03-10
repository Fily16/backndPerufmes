package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.*;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ConsolidadoService {

    public static final String COMPRA_TIENDA = "COMPRA TIENDA";

    /** Statuses that count as "active" orders in the consolidado */
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "SEPARADO", "PENDIENTE_RESTO", "PAGADO", "VERIFICADO"
    );

    private final ConsolidadoRepository consolidadoRepo;
    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final PricingService pricing;
    private final RetailService retailService;

    public ConsolidadoService(ConsolidadoRepository consolidadoRepo, OrderRepository orderRepo,
                              ProductRepository productRepo, PricingService pricing,
                              RetailService retailService) {
        this.consolidadoRepo = consolidadoRepo;
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.pricing = pricing;
        this.retailService = retailService;
    }

    public Consolidado getOrCreateActive() {
        return consolidadoRepo.findFirstByStatusOrderByCreatedAtDesc("ABIERTO")
                .orElseGet(() -> {
                    Consolidado c = new Consolidado();
                    c.setStatus("ABIERTO");
                    return consolidadoRepo.save(c);
                });
    }

    public Consolidado getActiveOrNull() {
        return consolidadoRepo.findFirstByStatusOrderByCreatedAtDesc("ABIERTO").orElse(null);
    }

    public List<Consolidado> getAll() {
        return consolidadoRepo.findAll();
    }

    public Consolidado getById(Long id) {
        return consolidadoRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Consolidado not found: " + id));
    }

    // --- Client Order Creation ---

    @Transactional
    public Order createOrder(OrderRequest request) {
        Consolidado active = consolidadoRepo.findFirstByStatusOrderByCreatedAtDesc("ABIERTO")
                .orElseThrow(() -> new RuntimeException("No hay consolidado abierto. No se pueden hacer pedidos en este momento."));

        Order order = new Order();
        order.setConsolidado(active);
        order.setClientName(request.getClientName());
        order.setClientPhone(request.getClientPhone());
        order.setPaymentStatus("PENDIENTE_SEPARACION");

        int totalUnits = 0;
        for (OrderRequest.OrderItemRequest itemReq : request.getItems()) {
            Product product = productRepo.findById(itemReq.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemReq.getProductId()));

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(itemReq.getQuantity());
            totalUnits += itemReq.getQuantity();

            double unitPrice = itemReq.getUnitPricePen() != null
                    ? itemReq.getUnitPricePen()
                    : (product.getWholesalePricePen() != null ? product.getWholesalePricePen() : 0);
            item.setUnitPricePen(unitPrice);
            item.calculateSubtotal();
            order.getItems().add(item);
        }

        order.recalculateTotal();

        // Calculate deposit: configurable per perfume (default S/20)
        double depositPerUnit = pricing.getDepositPerUnit();
        double depositAmount = totalUnits * depositPerUnit;
        order.setDepositAmountPen(depositAmount);
        order.setRemainingPen(order.getTotalPen() - depositAmount);

        Order saved = orderRepo.save(order);

        // Generate order code after save (uses ID)
        saved.setOrderCode("AS-" + String.format("%04d", saved.getId()));
        saved = orderRepo.save(saved);

        return saved;
    }

    // --- Payment Verification ---

    @Transactional
    public Order verifyDeposit(Long orderId, String yapeReference) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        order.setPaymentStatus("SEPARADO");
        order.setYapeReference(yapeReference);
        Order saved = orderRepo.save(order);
        recalculateConsolidado(order.getConsolidado().getId());
        return saved;
    }

    @Transactional
    public Order verifyRestPayment(Long orderId, String yapeReference) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        order.setPaymentStatus("PAGADO");
        order.setYapeReference(yapeReference);
        Order saved = orderRepo.save(order);
        recalculateConsolidado(order.getConsolidado().getId());
        return saved;
    }

    @Transactional
    public Order verifyPayment(Long orderId, String yapeReference) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        order.setPaymentStatus("VERIFICADO");
        order.setYapeReference(yapeReference);
        Order saved = orderRepo.save(order);
        recalculateConsolidado(order.getConsolidado().getId());
        return saved;
    }

    @Transactional
    public Order rejectPayment(Long orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        order.setPaymentStatus("RECHAZADO");
        Order saved = orderRepo.save(order);
        recalculateConsolidado(order.getConsolidado().getId());
        return saved;
    }

    // --- Consolidado Management ---

    @Transactional
    public Consolidado closeConsolidado(Long id) {
        Consolidado c = getById(id);
        c.setStatus("CERRADO");
        c.setCloseDate(LocalDateTime.now());
        recalculateConsolidado(id);
        return consolidadoRepo.save(c);
    }

    /**
     * Enable merchandise: moves store purchases to retail inventory
     * AND enables second payment for client orders (SEPARADO → PENDIENTE_RESTO).
     * Also marks consolidado as ENTREGADO.
     */
    @Transactional
    public Consolidado enableMerchandise(Long consolidadoId) {
        Consolidado c = getById(consolidadoId);

        List<Order> orders = orderRepo.findByConsolidado_Id(consolidadoId);

        for (Order order : orders) {
            boolean isStore = COMPRA_TIENDA.equals(order.getClientName());

            if (isStore && "VERIFICADO".equals(order.getPaymentStatus())) {
                // Move store purchases to retail inventory
                for (OrderItem item : order.getItems()) {
                    retailService.addStock(
                            item.getProduct().getId(),
                            item.getQuantity(),
                            item.getUnitPricePen(),
                            "Compra consolidado #" + consolidadoId);

                    // Auto-set retailPricePen if not set yet
                    Product product = item.getProduct();
                    if (product.getRetailPricePen() == null || product.getRetailPricePen() <= 0) {
                        double cost = item.getUnitPricePen() != null ? item.getUnitPricePen() : 0;
                        // Default retail price: cost + 50% margin
                        product.setRetailPricePen(Math.round(cost * 1.5 * 100.0) / 100.0);
                        productRepo.save(product);
                    }
                }
            }

            if (!isStore && "SEPARADO".equals(order.getPaymentStatus())) {
                // Enable second payment for clients
                order.setPaymentStatus("PENDIENTE_RESTO");
                orderRepo.save(order);
            }
        }

        c.setStatus("ENTREGADO");
        return consolidadoRepo.save(c);
    }

    public List<Order> getOrdersByConsolidado(Long consolidadoId) {
        return orderRepo.findByConsolidado_Id(consolidadoId);
    }

    public Order getOrderByCode(String code) {
        return orderRepo.findByOrderCode(code)
                .orElseThrow(() -> new RuntimeException("Pedido no encontrado con código: " + code));
    }

    /**
     * Recalculates consolidado totals based on active orders.
     */
    @Transactional
    public void recalculateConsolidado(Long consolidadoId) {
        Consolidado c = getById(consolidadoId);
        List<Order> activeOrders = orderRepo.findByConsolidado_Id(consolidadoId).stream()
                .filter(o -> ACTIVE_STATUSES.contains(o.getPaymentStatus()))
                .toList();

        int totalUnits = 0;
        int totalWeightG = 0;
        double subtotalProductsUsd = 0;
        double totalRevenuePen = 0;

        for (Order order : activeOrders) {
            for (OrderItem item : order.getItems()) {
                int qty = item.getQuantity();
                Product p = item.getProduct();
                totalUnits += qty;
                totalWeightG += (p.getWeightG() != null ? p.getWeightG() : 0) * qty;
                subtotalProductsUsd += (p.getPriceUsd() != null ? p.getPriceUsd() : 0) * qty;
                totalRevenuePen += item.getSubtotalPen() != null ? item.getSubtotalPen() : 0;
            }
        }

        double courierCostUsd = totalUnits > 0
                ? pricing.calculateTotalCourierCostUsd(totalWeightG, totalUnits) : 0;
        double extraShipping = pricing.calculateExtraMiamiShipping(subtotalProductsUsd);
        double investmentPen = pricing.calculateTotalInvestmentPen(subtotalProductsUsd, courierCostUsd, extraShipping);
        double profitPen = totalRevenuePen - investmentPen;

        c.setTotalWeightG(totalWeightG);
        c.setTotalCostUsd(subtotalProductsUsd);
        c.setCourierCostUsd(courierCostUsd);
        c.setTotalInvestmentPen(investmentPen);
        c.setTotalRevenuePen(totalRevenuePen);
        c.setProjectedProfitPen(profitPen);
        consolidadoRepo.save(c);
    }

    // --- Stock Purchase (Compra para Tienda) ---

    @Transactional
    public Order createStockPurchase(StockPurchaseRequest request) {
        Consolidado active = getOrCreateActive();

        Order order = new Order();
        order.setConsolidado(active);
        order.setClientName(COMPRA_TIENDA);
        order.setClientPhone("ADMIN");
        order.setPaymentStatus("VERIFICADO");

        for (StockPurchaseRequest.StockItem itemReq : request.getItems()) {
            Product product = productRepo.findById(itemReq.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemReq.getProductId()));

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(itemReq.getQuantity());

            double landedCost = pricing.calculateLandedCostUsd(
                    product.getPriceUsd() != null ? product.getPriceUsd() : 0,
                    product.getWeightG() != null ? product.getWeightG() : 0);
            double costPen = pricing.calculateCostPen(landedCost);
            item.setUnitPricePen(Math.round(costPen * 100.0) / 100.0);
            item.calculateSubtotal();
            order.getItems().add(item);
        }

        order.recalculateTotal();
        Order saved = orderRepo.save(order);

        // Generate order code
        saved.setOrderCode("CT-" + String.format("%04d", saved.getId()));
        saved = orderRepo.save(saved);

        recalculateConsolidado(active.getId());

        // NOTE: Do NOT add to retail inventory here.
        // Stock is added when admin clicks "Habilitar Mercancía" after shipment arrives.

        return saved;
    }

    public BreakdownSection previewStockPurchase(StockPurchaseRequest request) {
        List<OrderItem> items = new ArrayList<>();
        for (StockPurchaseRequest.StockItem itemReq : request.getItems()) {
            Product product = productRepo.findById(itemReq.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemReq.getProductId()));
            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(itemReq.getQuantity());
            double landedCost = pricing.calculateLandedCostUsd(
                    product.getPriceUsd() != null ? product.getPriceUsd() : 0,
                    product.getWeightG() != null ? product.getWeightG() : 0);
            item.setUnitPricePen(Math.round(pricing.calculateCostPen(landedCost) * 100.0) / 100.0);
            item.calculateSubtotal();
            items.add(item);
        }
        return buildBreakdown(items, false);
    }

    // --- Full Breakdown (3 sections) ---

    public FullBreakdownResponse getFullBreakdown(Long consolidadoId) {
        List<Order> allOrders = orderRepo.findByConsolidado_Id(consolidadoId).stream()
                .filter(o -> ACTIVE_STATUSES.contains(o.getPaymentStatus()))
                .toList();

        List<OrderItem> clientItems = new ArrayList<>();
        List<OrderItem> storeItems = new ArrayList<>();
        List<OrderItem> allItems = new ArrayList<>();
        int clientOrderCount = 0;
        int storeOrderCount = 0;

        for (Order order : allOrders) {
            boolean isStore = COMPRA_TIENDA.equals(order.getClientName());
            for (OrderItem item : order.getItems()) {
                allItems.add(item);
                if (isStore) {
                    storeItems.add(item);
                } else {
                    clientItems.add(item);
                }
            }
            if (isStore) storeOrderCount++;
            else clientOrderCount++;
        }

        BreakdownSection consolidado = buildBreakdown(clientItems, true);
        consolidado.setOrderCount(clientOrderCount);

        BreakdownSection miCompra = buildBreakdown(storeItems, false);
        miCompra.setOrderCount(storeOrderCount);

        BreakdownSection total = buildBreakdown(allItems, true);
        total.setOrderCount(allOrders.size());
        total.setTotalRevenuePen(consolidado.getTotalRevenuePen());
        total.setProfitPen(consolidado.getTotalRevenuePen() != null
                ? consolidado.getTotalRevenuePen() - total.getTotalInvestmentPen() : null);
        if (total.getProfitPen() != null && total.getTotalInvestmentPen() > 0) {
            total.setMarginPercent(total.getProfitPen() / total.getTotalInvestmentPen() * 100);
        }

        FullBreakdownResponse response = new FullBreakdownResponse();
        response.setConsolidado(consolidado);
        response.setMiCompra(miCompra);
        response.setTotal(total);
        return response;
    }

    private BreakdownSection buildBreakdown(List<OrderItem> items, boolean includeRevenue) {
        BreakdownSection s = new BreakdownSection();
        int totalUnits = 0;
        int weightPerfumesG = 0;
        double subtotalProductsUsd = 0;
        double totalRevenuePen = 0;

        for (OrderItem item : items) {
            int qty = item.getQuantity();
            Product p = item.getProduct();
            totalUnits += qty;
            weightPerfumesG += (p.getWeightG() != null ? p.getWeightG() : 0) * qty;
            subtotalProductsUsd += (p.getPriceUsd() != null ? p.getPriceUsd() : 0) * qty;
            totalRevenuePen += item.getSubtotalPen() != null ? item.getSubtotalPen() : 0;
        }

        int boxesNeeded = totalUnits > 0 ? pricing.calculateBoxesNeeded(totalUnits) : 0;
        int weightBoxesG = (int) (boxesNeeded * pricing.getBoxWeightG());
        int totalWeightG = weightPerfumesG + weightBoxesG;

        double courierCostUsd = totalUnits > 0
                ? pricing.calculateTotalCourierCostUsd(weightPerfumesG, totalUnits) : 0;
        double extraMiamiUsd = pricing.calculateExtraMiamiShipping(subtotalProductsUsd);
        double totalCostUsd = subtotalProductsUsd + courierCostUsd + extraMiamiUsd;
        double exchangeRate = pricing.getExchangeRate();
        double investmentPen = totalCostUsd * exchangeRate;

        s.setTotalUnits(totalUnits);
        s.setWeightPerfumesG(weightPerfumesG);
        s.setBoxesNeeded(boxesNeeded);
        s.setWeightBoxesG(weightBoxesG);
        s.setTotalWeightG(totalWeightG);
        s.setSubtotalProductsUsd(Math.round(subtotalProductsUsd * 100.0) / 100.0);
        s.setCourierCostUsd(Math.round(courierCostUsd * 100.0) / 100.0);
        s.setExtraMiamiUsd(Math.round(extraMiamiUsd * 100.0) / 100.0);
        s.setTotalCostUsd(Math.round(totalCostUsd * 100.0) / 100.0);
        s.setExchangeRate(exchangeRate);
        s.setTotalInvestmentPen(Math.round(investmentPen * 100.0) / 100.0);

        if (includeRevenue) {
            s.setTotalRevenuePen(Math.round(totalRevenuePen * 100.0) / 100.0);
            double profitPen = totalRevenuePen - investmentPen;
            s.setProfitPen(Math.round(profitPen * 100.0) / 100.0);
            if (investmentPen > 0) {
                s.setMarginPercent(Math.round(profitPen / investmentPen * 10000.0) / 100.0);
            }
        }

        return s;
    }
}
