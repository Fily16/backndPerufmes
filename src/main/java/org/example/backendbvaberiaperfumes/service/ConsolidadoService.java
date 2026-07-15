package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.dto.*;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConsolidadoService {

    public static final String COMPRA_TIENDA = "COMPRA TIENDA";

    /** Statuses that count as "active" orders in the consolidado */
    // Solo los pedidos ACEPTADOS (separados en adelante) cuentan para demanda, compra,
    // KPIs y faltantes. Los pendientes de separación y los rechazados NO cuentan: el admin
    // debe darle "Aceptar" a un pedido para que entre al consolidado.
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "SEPARADO", "PENDIENTE_RESTO", "PAGADO", "VERIFICADO"
    );

    private final ConsolidadoRepository consolidadoRepo;
    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final PricingService pricing;
    private final RetailService retailService;
    private final PromotionRepository promotionRepo;
    private final CostBasisService costBasis;
    private final PurchasePlanRepository planRepo;

    public ConsolidadoService(ConsolidadoRepository consolidadoRepo, OrderRepository orderRepo,
                              ProductRepository productRepo, PricingService pricing,
                              RetailService retailService, PromotionRepository promotionRepo,
                              CostBasisService costBasis, PurchasePlanRepository planRepo) {
        this.consolidadoRepo = consolidadoRepo;
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.pricing = pricing;
        this.retailService = retailService;
        this.promotionRepo = promotionRepo;
        this.costBasis = costBasis;
        this.planRepo = planRepo;
    }

    private static double nz(Double v) { return v != null ? v : 0; }

    /**
     * LEGACY (solo flujos admin): el mas nuevo ABIERTO o crea uno vacio.
     * El publico ya NO pasa por aqui: /current y /active son de solo lectura;
     * los consolidados nuevos se abren explicitamente con openConsolidado().
     */
    public Consolidado getOrCreateActive() {
        return consolidadoRepo.findFirstByStatusOrderByCreatedAtDesc("ABIERTO")
                .orElseGet(() -> {
                    Consolidado c = new Consolidado();
                    c.setStatus("ABIERTO");
                    return consolidadoRepo.save(c);
                });
    }

    // =====================================================================
    // Ciclo de vida programado (consolidados v2)
    // =====================================================================

    /** ¿Acepta pedidos por encargo? ABIERTO y (sin plazo o plazo vigente). */
    public boolean isOpenForOrders(Consolidado c) {
        if (c == null || !"ABIERTO".equals(c.getStatus())) return false;
        return c.getEndsAt() == null || c.getEndsAt().isAfter(java.time.Instant.now());
    }

    /**
     * Estado publico para el aviso/countdown. NUNCA crea filas.
     * Prioriza ABIERTO/PROGRAMADO; si no hay, devuelve el mas nuevo de cualquier
     * status (para poder decir "cerrado, espera el proximo").
     */
    public ConsolidadoPublicDTO getCurrentPublic() {
        Consolidado c = consolidadoRepo
                .findFirstByStatusInOrderByCreatedAtDesc(List.of("ABIERTO", "PROGRAMADO"))
                .orElseGet(() -> consolidadoRepo.findFirstByOrderByCreatedAtDesc().orElse(null));
        ConsolidadoPublicDTO dto = new ConsolidadoPublicDTO();
        dto.serverNowMs = System.currentTimeMillis();
        if (c == null) {
            dto.status = "NONE";
            dto.open = false;
            return dto;
        }
        dto.id = c.getId();
        dto.status = c.getStatus();
        dto.title = c.getTitle();
        dto.description = c.getDescription();
        dto.startAtMs = c.getStartAt() != null ? c.getStartAt().toEpochMilli() : null;
        dto.endsAtMs = c.getEndsAt() != null ? c.getEndsAt().toEpochMilli() : null;
        dto.extended = Boolean.TRUE.equals(c.getExtended());
        dto.imageMediaId = c.getImageMediaId();
        dto.open = isOpenForOrders(c);
        return dto;
    }

    /**
     * Abre (o programa) un consolidado nuevo con fechas, titulo e imagen.
     * Falla si ya existe uno ABIERTO o PROGRAMADO.
     */
    @Transactional
    public Consolidado openConsolidado(String title, String description,
                                       Long startAtMs, Long endsAtMs, Long imageMediaId) {
        if (consolidadoRepo.countByStatusIn(List.of("ABIERTO", "PROGRAMADO")) > 0) {
            throw new IllegalStateException("Ya existe un consolidado abierto o programado.");
        }
        if (endsAtMs == null) {
            throw new IllegalArgumentException("La fecha de cierre es obligatoria.");
        }
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant startAt = startAtMs != null ? java.time.Instant.ofEpochMilli(startAtMs) : now;
        java.time.Instant endsAt = java.time.Instant.ofEpochMilli(endsAtMs);
        if (!endsAt.isAfter(startAt)) {
            throw new IllegalArgumentException("El cierre debe ser posterior a la apertura.");
        }
        if (endsAt.isBefore(now)) {
            throw new IllegalArgumentException("El cierre no puede estar en el pasado.");
        }
        Consolidado c = new Consolidado();
        c.setTitle(title);
        c.setDescription(description);
        c.setStartAt(startAt);
        c.setEndsAt(endsAt);
        c.setImageMediaId(imageMediaId);
        c.setExtended(false);
        if (startAt.isAfter(now)) {
            c.setStatus("PROGRAMADO");
        } else {
            c.setStatus("ABIERTO");
            c.setOpenDate(LocalDateTime.now());
        }
        return consolidadoRepo.save(c);
    }

    /**
     * Configura plazo/titulo/imagen de un consolidado ABIERTO o PROGRAMADO.
     * Si esta ABIERTO y el nuevo cierre es POSTERIOR al anterior -> plazo extendido
     * (el aviso publico cambia a "¡Plazo extendido! Aprovecha").
     */
    @Transactional
    public Consolidado updateSchedule(Long id, String title, String description,
                                      Long startAtMs, Long endsAtMs, Long imageMediaId) {
        Consolidado c = getById(id);
        if (!"ABIERTO".equals(c.getStatus()) && !"PROGRAMADO".equals(c.getStatus())) {
            throw new IllegalStateException("Solo se puede configurar un consolidado abierto o programado.");
        }
        if (title != null) c.setTitle(title.isBlank() ? null : title.trim());
        if (description != null) c.setDescription(description.isBlank() ? null : description.trim());
        if (imageMediaId != null) c.setImageMediaId(imageMediaId <= 0 ? null : imageMediaId);
        if (startAtMs != null && "PROGRAMADO".equals(c.getStatus())) {
            c.setStartAt(java.time.Instant.ofEpochMilli(startAtMs));
        }
        if (endsAtMs != null) {
            java.time.Instant newEnds = java.time.Instant.ofEpochMilli(endsAtMs);
            if ("ABIERTO".equals(c.getStatus()) && c.getEndsAt() != null && newEnds.isAfter(c.getEndsAt())) {
                c.setExtended(true);
            }
            c.setEndsAt(newEnds);
        }
        if (c.getStartAt() != null && c.getEndsAt() != null && !c.getEndsAt().isAfter(c.getStartAt())) {
            throw new IllegalArgumentException("El cierre debe ser posterior a la apertura.");
        }
        return consolidadoRepo.save(c);
    }

    /** PROGRAMADO -> ABIERTO (lo invoca el scheduler al llegar la fecha de apertura). */
    @Transactional
    public Consolidado activateScheduled(Long id) {
        Consolidado c = getById(id);
        if (!"PROGRAMADO".equals(c.getStatus())) return c;
        c.setStatus("ABIERTO");
        c.setOpenDate(LocalDateTime.now());
        return consolidadoRepo.save(c);
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

    // --- Client Order Creation (Con soporte para acumular pedidos) ---

    @Transactional
    public Order createOrder(OrderRequest request) {
        Order order;

        // 0. Resolver el canal PRIMERO: el gating del consolidado solo aplica a pedidos
        //    por encargo (CONSOLIDADO). Los de STOCK/promos nunca dependen del plazo.
        //    Un pedido que trae promociones (packs) es siempre canal STOCK.
        boolean hasPromos = request.getPromotions() != null && !request.getPromotions().isEmpty();
        String channel = (hasPromos || "STOCK".equalsIgnoreCase(request.getChannel())) ? "STOCK" : "CONSOLIDADO";

        // 1. ¿Agregar a un pedido existente?
        if (request.getExistingOrderCode() != null && !request.getExistingOrderCode().trim().isEmpty()) {
            order = orderRepo.findByOrderCode(request.getExistingOrderCode().trim())
                    .orElseThrow(() -> new IllegalArgumentException("El código de pedido ingresado no existe."));

            if (!order.getClientPhone().replaceAll("\\s+", "").equals(
                    request.getClientPhone().replaceAll("\\s+", ""))) {
                throw new IllegalArgumentException("El celular debe coincidir con el pedido original.");
            }

            // El canal de un pedido queda fijado al crearlo y NO se puede cambiar al
            // acumular: si no, un cliente podria mandar channel=STOCK sobre su pedido
            // por encargo y (a) saltarse el candado del plazo y (b) sacar del lote un
            // pedido ya pagado (los computos del consolidado ignoran el canal STOCK).
            if (order.getChannel() != null && !order.getChannel().equals(channel)) {
                throw new IllegalArgumentException(
                        "Este pedido es de " + ("STOCK".equals(order.getChannel()) ? "entrega inmediata" : "encargo")
                                + ". Haz un pedido aparte para el otro tipo de compra.");
            }
            channel = order.getChannel() != null ? order.getChannel() : channel;

            // El candado de plazo aplica SOLO al canal por encargo: agregar a un pedido de
            // stock sigue permitido aunque el consolidado ya haya cerrado.
            if ("CONSOLIDADO".equals(channel) && !isOpenForOrders(order.getConsolidado())) {
                throw new IllegalArgumentException("El consolidado de este pedido ya fue cerrado.");
            }

            String oldYape = order.getYapeReference() != null ? order.getYapeReference() : "Sin Voucher";
            String newYape = request.getYapeReference() != null ? request.getYapeReference() : "Sin Voucher";
            order.setYapeReference(oldYape + " | " + newYape);
            order.setPaymentStatus("PENDIENTE_SEPARACION");

        } else {
            // 2. Pedido totalmente nuevo
            Consolidado anchor;
            if ("CONSOLIDADO".equals(channel)) {
                // Por encargo: exige consolidado ABIERTO con plazo vigente (fuente de verdad).
                anchor = consolidadoRepo.findFirstByStatusOrderByCreatedAtDesc("ABIERTO")
                        .filter(this::isOpenForOrders)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "El consolidado está cerrado. Espera la apertura del próximo."));
            } else {
                // STOCK: venta inmediata. Se ancla al consolidado mas nuevo SIN importar su
                // status (los computos del consolidado ya excluyen el canal STOCK); un FK null
                // lo haria invisible en el panel admin.
                anchor = consolidadoRepo.findFirstByOrderByCreatedAtDesc()
                        .orElseThrow(() -> new RuntimeException("No hay consolidado registrado."));
            }

            order = new Order();
            order.setConsolidado(anchor);
            order.setClientName(request.getClientName());
            order.setClientPhone(request.getClientPhone());
            order.setYapeReference(request.getYapeReference());
            order.setPaymentStatus("PENDIENTE_SEPARACION");

            // Inicializar la lista si es nuevo
            order.setItems(new ArrayList<>());
        }

        // 3. Actualizar datos de envío si el cliente los cambió o los llenó por primera vez
        if (request.getDeliveryMethod() != null) order.setDeliveryMethod(request.getDeliveryMethod());
        if (request.getShippingName() != null) order.setShippingName(request.getShippingName());
        if (request.getShippingDni() != null) order.setShippingDni(request.getShippingDni());
        if (request.getShippingPhone() != null) order.setShippingPhone(request.getShippingPhone());
        if (request.getShippingAddress() != null) order.setShippingAddress(request.getShippingAddress());
        if (request.getShippingDepartment() != null) order.setShippingDepartment(request.getShippingDepartment());
        if (request.getShippingAgency() != null) order.setShippingAgency(request.getShippingAgency());

        order.setChannel(channel);

        // 4. Agregar los nuevos productos (y fusionarlos si ya existen)
        int newUnitsAdded = 0; // Contamos solo lo nuevo que entra en este carrito
        java.util.Map<Long, Integer> retailStock = retailService.getStockByProduct();

        List<OrderRequest.OrderItemRequest> itemReqs = request.getItems() != null
                ? request.getItems() : new ArrayList<>();
        for (OrderRequest.OrderItemRequest itemReq : itemReqs) {
            Product product = productRepo.findById(itemReq.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemReq.getProductId()));

            newUnitsAdded += itemReq.getQuantity();

            // Buscar si el perfume ya estaba en la orden
            OrderItem existingItem = null;
            if (order.getItems() != null) {
                for (OrderItem oi : order.getItems()) {
                    if (oi.getProduct().getId().equals(product.getId())) {
                        existingItem = oi;
                        break;
                    }
                }
            }

            // Precio por CANAL (el backend manda, no el cliente).
            double unitPrice;
            if ("STOCK".equals(channel)) {
                if (product.getStockPricePen() == null) {
                    throw new IllegalArgumentException(product.getBrand() + " " + product.getName() + " no está disponible en stock.");
                }
                Integer stockQty = retailStock.get(product.getId());
                if (stockQty == null || stockQty < itemReq.getQuantity()) {
                    throw new IllegalArgumentException("No hay stock suficiente de " + product.getBrand() + " " + product.getName() + ".");
                }
                unitPrice = product.getStockPricePen();
            } else {
                unitPrice = product.getWholesalePricePen() != null ? product.getWholesalePricePen() : 0;
            }

            if (existingItem != null) {
                existingItem.setQuantity(existingItem.getQuantity() + itemReq.getQuantity());
                existingItem.setUnitPricePen(unitPrice);
                snapshotCost(existingItem, product); // el precio se actualizo: el costo tambien
                existingItem.calculateSubtotal();
            } else {
                OrderItem item = new OrderItem();
                item.setOrder(order);
                item.setProduct(product);
                item.setQuantity(itemReq.getQuantity());
                item.setUnitPricePen(unitPrice);
                snapshotCost(item, product);
                item.calculateSubtotal();
                order.getItems().add(item);
            }
        }

        // 4b. Agregar promociones (packs). Stock propio de la promo, independiente del retail.
        if (hasPromos) {
            for (OrderRequest.PromoLineRequest pl : request.getPromotions()) {
                if (pl.getPromotionId() == null) continue;
                Promotion promo = promotionRepo.findById(pl.getPromotionId())
                        .orElseThrow(() -> new IllegalArgumentException("La promoción seleccionada ya no existe."));
                int qty = pl.getQuantity() != null && pl.getQuantity() > 0 ? pl.getQuantity() : 1;
                if (!Boolean.TRUE.equals(promo.getActive())
                        || promo.getStockQty() == null || promo.getStockQty() < qty) {
                    throw new IllegalArgumentException("La promoción \"" + promo.getName() + "\" ya no tiene stock suficiente.");
                }
                OrderPromo op = new OrderPromo();
                op.setOrder(order);
                op.setPromotionId(promo.getId());
                op.setPromoName(promo.getName());
                op.setUnitPricePen(promo.getPricePen());
                op.setQuantity(qty);
                op.setProfitPen(promo.getProfitPen());
                op.calculateSubtotal();
                order.getPromos().add(op);
                newUnitsAdded += qty;
            }
        }

        order.recalculateTotal();

        // 5. Cobrar SOLO la separación de las unidades nuevas añadidas
        double currentDeposit = order.getDepositAmountPen() != null ? order.getDepositAmountPen() : 0.0;
        double addedDeposit = newUnitsAdded * pricing.getDepositPerUnit();
        order.setDepositAmountPen(currentDeposit + addedDeposit);
        order.setRemainingPen(order.getTotalPen() - order.getDepositAmountPen());

        Order saved = orderRepo.save(order);

        // 6. Generar el código AS-XXXX solo si es nuevo
        if (saved.getOrderCode() == null || saved.getOrderCode().isEmpty()) {
            saved.setOrderCode("AS-" + String.format("%04d", saved.getId()));
            saved = orderRepo.save(saved);
        } else {
            recalculateConsolidado(saved.getConsolidado().getId());
        }

        return saved;
    }

    // --- Payment Verification ---

    @Transactional
    public Order verifyDeposit(Long orderId, String yapeReference) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        boolean wasPending = "PENDIENTE_SEPARACION".equals(order.getPaymentStatus());

        // Pedido de STOCK: aceptar = venta inmediata. Pasa a VERIFICADO, descuenta el
        // stock de tienda y registra la ganancia (precio venta − costo). No es del consolidado.
        if ("STOCK".equals(order.getChannel())) {
            order.setPaymentStatus("VERIFICADO");
            if (yapeReference != null && !yapeReference.isBlank()) order.setYapeReference(yapeReference);
            Order saved = orderRepo.save(order);
            if (wasPending) {
                for (OrderItem it : order.getItems()) {
                    try {
                        retailService.registerSale(it.getProduct().getId(), it.getQuantity(),
                                it.getUnitPricePen() != null ? it.getUnitPricePen() : 0, "TIENDA");
                    } catch (Exception ignored) { /* sin stock suficiente: no bloquear la aceptación */ }
                }
                // Promociones: descontar el stock propio del pack (independiente del retail).
                for (OrderPromo op : order.getPromos()) {
                    if (op.getPromotionId() == null) continue;
                    promotionRepo.findById(op.getPromotionId()).ifPresent(promo -> {
                        int current = promo.getStockQty() != null ? promo.getStockQty() : 0;
                        promo.setStockQty(Math.max(0, current - op.getQuantity()));
                        promotionRepo.save(promo);
                    });
                }
            }
            return saved;
        }

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

        // Refrescar totales/ganancia con la fórmula actual antes de sellar la entrega.
        recalculateConsolidado(consolidadoId);
        c.setStatus("ENTREGADO");
        if (c.getDeliveredAt() == null) c.setDeliveredAt(LocalDateTime.now());
        return consolidadoRepo.save(c);
    }

    @Transactional
    public void deleteConsolidado(Long id) {
        Consolidado c = getById(id);
        if ("ABIERTO".equals(c.getStatus())) {
            throw new RuntimeException("No se puede eliminar un consolidado abierto.");
        }
        List<Order> orders = orderRepo.findByConsolidado_Id(id);
        orderRepo.deleteAll(orders);
        consolidadoRepo.delete(c);
    }

    public List<Order> getOrdersByConsolidado(Long consolidadoId) {
        return orderRepo.findByConsolidado_Id(consolidadoId);
    }

    public Order getOrderByCode(String code) {
        return orderRepo.findByOrderCode(code)
                .orElseThrow(() -> new RuntimeException("Pedido no encontrado con código: " + code));
    }

    /**
     * Demanda agregada de CLIENTES (excluye COMPRA TIENDA) por producto, para el motor de asignacion.
     * Solo cuenta pedidos en estados activos. Transaccional para inicializar los items (lazy).
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> getActiveDemandByProduct(Long consolidadoId) {
        Map<Long, Integer> demand = new LinkedHashMap<>();
        for (Order order : orderRepo.findByConsolidado_Id(consolidadoId)) {
            if (!ACTIVE_STATUSES.contains(order.getPaymentStatus())) continue;
            if (COMPRA_TIENDA.equals(order.getClientName())) continue;
            if ("STOCK".equals(order.getChannel())) continue; // los de stock ya los tienes
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null) {
                    demand.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
                }
            }
        }
        return demand;
    }

    /**
     * Precio unitario PEN promedio YA COBRADO por producto (desde los snapshots de los
     * items activos). Es el ingreso real contra el que la asignacion mide el margen.
     * Transaccional para inicializar los items (lazy).
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> getActiveAvgPriceByProduct(Long consolidadoId) {
        Map<Long, double[]> acc = new LinkedHashMap<>(); // pid -> [unidades, ingreso]
        for (Order order : orderRepo.findByConsolidado_Id(consolidadoId)) {
            if (!ACTIVE_STATUSES.contains(order.getPaymentStatus())) continue;
            if (COMPRA_TIENDA.equals(order.getClientName())) continue;
            if ("STOCK".equals(order.getChannel())) continue;
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() == null || item.getSubtotalPen() == null) continue;
                double[] a = acc.computeIfAbsent(item.getProduct().getId(), k -> new double[2]);
                a[0] += item.getQuantity();
                a[1] += item.getSubtotalPen();
            }
        }
        Map<Long, Double> avg = new LinkedHashMap<>();
        for (Map.Entry<Long, double[]> e : acc.entrySet()) {
            if (e.getValue()[0] > 0) avg.put(e.getKey(), e.getValue()[1] / e.getValue()[0]);
        }
        return avg;
    }

    // --- Client self-edit order ---
    @Transactional
    public Order editOrderByClient(String orderCode, String clientPhone,
                                   List<OrderRequest.OrderItemRequest> newItems) {
        Order order = orderRepo.findByOrderCode(orderCode.trim())
                .orElseThrow(() -> new IllegalArgumentException("El código de pedido no existe."));

        String storedPhone = order.getClientPhone().replaceAll("\\s+", "");
        String inputPhone = clientPhone.replaceAll("\\s+", "");
        if (!storedPhone.equals(inputPhone)) {
            throw new IllegalArgumentException("El celular no coincide con el pedido.");
        }

        // Mismo candado que para crear, y con la misma excepcion: solo los pedidos por
        // encargo dependen del plazo. Un pedido de stock se ancla al consolidado mas
        // nuevo (aunque este cerrado), asi que bloquearlo por el plazo seria un bug.
        if ("CONSOLIDADO".equals(order.getChannel()) && !isOpenForOrders(order.getConsolidado())) {
            throw new IllegalArgumentException("El consolidado ya fue cerrado, no se puede editar.");
        }

        // Save old deposit and units BEFORE touching items
        double originalDeposit = order.getDepositAmountPen() != null ? order.getDepositAmountPen() : 0;
        int oldUnits = 0;
        for (OrderItem oi : order.getItems()) {
            oldUnits += oi.getQuantity();
        }

        // Clear existing items (orphanRemoval=true deletes them from DB)
        order.getItems().clear();

        // Add new items
        int newTotalUnits = 0;
        for (OrderRequest.OrderItemRequest itemReq : newItems) {
            Product product = productRepo.findById(itemReq.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemReq.getProductId()));

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(itemReq.getQuantity());

            double unitPrice = itemReq.getUnitPricePen() != null
                    ? itemReq.getUnitPricePen()
                    : (product.getWholesalePricePen() != null ? product.getWholesalePricePen() : 0);
            item.setUnitPricePen(unitPrice);
            item.calculateSubtotal();
            order.getItems().add(item);
            newTotalUnits += itemReq.getQuantity();
        }

        order.recalculateTotal();

        // Deposit: recalculate based on current total units
        double depositPerUnit = pricing.getDepositPerUnit();
        double correctDeposit = newTotalUnits * depositPerUnit;

        if (newTotalUnits > oldUnits) {
            // Client added more units → needs to pay extra, mark as pending
            order.setDepositAmountPen(correctDeposit);
            order.setPaymentStatus("PENDIENTE_SEPARACION");
        } else {
            // Same or fewer units → adjust deposit to match actual units
            order.setDepositAmountPen(correctDeposit);
        }
        order.setRemainingPen(order.getTotalPen() - order.getDepositAmountPen());

        Order saved = orderRepo.save(order);
        recalculateConsolidado(order.getConsolidado().getId());
        return saved;
    }

    /** Congela costo y peso del producto en el item (misma base que definio su precio). */
    private void snapshotCost(OrderItem item, Product product) {
        Double costUsd = costBasis.basisCostUsdOrLegacy(product);
        item.setUnitCostUsdSnapshot(costUsd);
        item.setWeightGSnapshot(product.getWeightG() != null ? product.getWeightG() : 600);
    }

    @Transactional
    public void recalculateConsolidado(Long consolidadoId) {
        Consolidado c = getById(consolidadoId);
        List<Order> activeOrders = orderRepo.findByConsolidado_Id(consolidadoId).stream()
                .filter(o -> ACTIVE_STATUSES.contains(o.getPaymentStatus()))
                .filter(o -> !"STOCK".equals(o.getChannel()))          // los de stock no son del consolidado
                .filter(o -> !COMPRA_TIENDA.equals(o.getClientName())) // compras internas tampoco son ingreso de cliente
                .toList();

        int totalUnits = 0;
        int totalWeightG = 0;
        double subtotalProductsUsd = 0;
        double totalRevenuePen = 0;
        double landedCostPen = 0; // costo puesto en Perú por unidad (misma fórmula única del precio)

        // Con plan de compra CONFIRMADO, el costo por producto es el REAL decidido
        // (proveedor elegido por el optimizador), no el asumido al publicar el precio.
        Map<Long, Double> planCost = new HashMap<>();
        planRepo.findFirstByConsolidadoIdAndStatusOrderByCreatedAtDesc(consolidadoId, "CONFIRMED")
                .ifPresent(plan -> {
                    for (PurchasePlanLine l : plan.getLines()) {
                        if (l.getUnitCostUsd() != null) planCost.put(l.getProductId(), l.getUnitCostUsd());
                    }
                });

        for (Order order : activeOrders) {
            for (OrderItem item : order.getItems()) {
                int qty = item.getQuantity();
                Product p = item.getProduct();
                // Costo: plan confirmado > snapshot del pedido > base de costo viva.
                Double fromPlan = planCost.get(p.getId());
                double priceUsd = fromPlan != null ? fromPlan
                        : (item.getUnitCostUsdSnapshot() != null
                            ? item.getUnitCostUsdSnapshot()
                            : nz(costBasis.basisCostUsdOrLegacy(p)));
                int weightG = item.getWeightGSnapshot() != null
                        ? item.getWeightGSnapshot()
                        : (p.getWeightG() != null ? p.getWeightG() : 0);
                totalUnits += qty;
                totalWeightG += weightG * qty;
                subtotalProductsUsd += priceUsd * qty;
                totalRevenuePen += item.getSubtotalPen() != null ? item.getSubtotalPen() : 0;
                landedCostPen += pricing.landedPen(priceUsd, weightG) * qty;
            }
        }

        // Ganancia = Σ (precio de venta − costo puesto en Perú) por unidad. Coincide con el +20/perfume
        // del modelo de precios (Consolidado) y es ROBUSTA a cambios de T/C/courier: usa la misma
        // fórmula única (PricingService.landedPen) que define el precio, no un courier agregado aparte.
        double courierCostUsd = totalUnits > 0
                ? pricing.calculateTotalCourierCostUsd(totalWeightG, totalUnits) : 0;
        double profitPen = totalRevenuePen - landedCostPen;

        c.setTotalWeightG(totalWeightG);
        c.setTotalCostUsd(subtotalProductsUsd);
        c.setCourierCostUsd(courierCostUsd);
        c.setTotalInvestmentPen(Math.round(landedCostPen * 100.0) / 100.0);
        c.setTotalRevenuePen(Math.round(totalRevenuePen * 100.0) / 100.0);
        c.setProjectedProfitPen(Math.round(profitPen * 100.0) / 100.0);
        consolidadoRepo.save(c);
    }

    // --- Stock Purchase (Compra para Tienda) ---

    @Transactional
    public Order createStockPurchase(StockPurchaseRequest request) {
        // La compra de tienda tambien se hace despues del cierre (antes de ENTREGADO).
        // NO usa getOrCreateActive(): crearia un ABIERTO fantasma sin fechas.
        Consolidado active = consolidadoRepo
                .findFirstByStatusInOrderByCreatedAtDesc(List.of("ABIERTO", "CERRADO"))
                .orElseThrow(() -> new IllegalStateException(
                        "No hay consolidado activo para la compra de tienda. Abre uno primero."));

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

            double costUsd = nz(costBasis.basisCostUsdOrLegacy(product));
            double landedCost = pricing.calculateLandedCostUsd(costUsd,
                    product.getWeightG() != null ? product.getWeightG() : 0);
            double costPen = pricing.calculateCostPen(landedCost);
            item.setUnitPricePen(Math.round(costPen * 100.0) / 100.0);
            snapshotCost(item, product);
            item.calculateSubtotal();
            order.getItems().add(item);
        }

        order.recalculateTotal();
        Order saved = orderRepo.save(order);

        // Generate order code
        saved.setOrderCode("CT-" + String.format("%04d", saved.getId()));
        saved = orderRepo.save(saved);

        recalculateConsolidado(active.getId());

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
            double costUsd = nz(costBasis.basisCostUsdOrLegacy(product));
            double landedCost = pricing.calculateLandedCostUsd(costUsd,
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
                .filter(o -> !"STOCK".equals(o.getChannel())) // los de stock no son del consolidado
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

        // Aggregate quantity per product
        Map<Long, int[]> productQtyMap = new LinkedHashMap<>();
        Map<Long, Product> productMap = new HashMap<>();

        for (OrderItem item : items) {
            int qty = item.getQuantity();
            Product p = item.getProduct();
            totalUnits += qty;
            weightPerfumesG += (p.getWeightG() != null ? p.getWeightG() : 0) * qty;
            subtotalProductsUsd += (p.getPriceUsd() != null ? p.getPriceUsd() : 0) * qty;
            totalRevenuePen += item.getSubtotalPen() != null ? item.getSubtotalPen() : 0;

            productQtyMap.computeIfAbsent(p.getId(), k -> new int[]{0})[0] += qty;
            productMap.putIfAbsent(p.getId(), p);
        }

        // Build per-product counts sorted by quantity desc
        List<ProductCount> productCounts = productQtyMap.entrySet().stream()
                .map(e -> {
                    Product p = productMap.get(e.getKey());
                    return new ProductCount(p.getId(), p.getBrand(), p.getName(),
                            p.getMl() != null ? p.getMl() : 0, e.getValue()[0]);
                })
                .sorted(Comparator.comparingInt(ProductCount::getQuantity).reversed())
                .collect(Collectors.toList());
        s.setProductCounts(productCounts);

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