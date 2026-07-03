package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.config.CurrentAdminProvider;
import org.example.backendbvaberiaperfumes.dto.OrderRequest;
import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.model.OrderItem;
import org.example.backendbvaberiaperfumes.repository.OrderItemRepository;
import org.example.backendbvaberiaperfumes.repository.OrderRepository;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.example.backendbvaberiaperfumes.service.EmailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final ConsolidadoService consolidadoService;
    private final OrderRepository orderRepo;
    private final OrderItemRepository orderItemRepo;
    private final CurrentAdminProvider currentAdmin;
    private final EmailService emailService;

    public OrderController(ConsolidadoService consolidadoService, OrderRepository orderRepo,
                          OrderItemRepository orderItemRepo, CurrentAdminProvider currentAdmin,
                          EmailService emailService) {
        this.consolidadoService = consolidadoService;
        this.orderRepo = orderRepo;
        this.orderItemRepo = orderItemRepo;
        this.currentAdmin = currentAdmin;
        this.emailService = emailService;
    }

    /** Marca qué vendedor (admin) gestionó el pedido y lo guarda. */
    private Order tagAttended(Order order) {
        Admin admin = currentAdmin.current();
        if (admin != null && order != null) {
            order.setAttendedBy(admin.getName());
            order.setAttendedById(admin.getId());
            return orderRepo.save(order);
        }
        return order;
    }

    // Public: client creates an order
    @PostMapping
    public ResponseEntity<?> createOrder(@Valid @RequestBody OrderRequest request) {
        try {
            Order order = consolidadoService.createOrder(request);
            // Notificar por correo cuando el cliente finaliza su pedido.
            emailService.sendNewOrderNotification(order.getId());
            return ResponseEntity.ok(order);
        } catch (IllegalArgumentException e) {
            // Si hay un error (ej. el código no existe o el celular no coincide), devolvemos un 400 amigable
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            // Error general del servidor
            return ResponseEntity.status(500).body(Map.of("message", "Error interno al procesar el pedido."));
        }
    }

    // Admin: get all orders (con filtros opcionales del ERP)
    @GetMapping
    public List<Order> getAll(@RequestParam(required = false) String status,
                              @RequestParam(required = false) String deliveryMethod,
                              @RequestParam(required = false) String seller,
                              @RequestParam(required = false) Long consolidadoId) {
        List<Order> list = (status != null) ? orderRepo.findByPaymentStatus(status) : orderRepo.findAll();
        return list.stream()
                .filter(o -> deliveryMethod == null || deliveryMethod.equalsIgnoreCase(o.getDeliveryMethod()))
                .filter(o -> seller == null || seller.equalsIgnoreCase(o.getAttendedBy()))
                .filter(o -> consolidadoId == null || consolidadoId.equals(o.getConsolidadoId()))
                .collect(Collectors.toList());
    }

    // Admin: get order by id
    @GetMapping("/{id}")
    public ResponseEntity<Order> getById(@PathVariable Long id) {
        return orderRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Public: get order by code (client looks up their order)
    @GetMapping("/code/{code}")
    public ResponseEntity<Order> getByCode(@PathVariable String code) {
        Order order = consolidadoService.getOrderByCode(code);
        return ResponseEntity.ok(order);
    }

    // Admin: verify deposit (separación)
    @PutMapping("/{id}/verify-deposit")
    public ResponseEntity<Order> verifyDeposit(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Order order = consolidadoService.verifyDeposit(id, body.get("yapeReference"));
        return ResponseEntity.ok(tagAttended(order));
    }

    // Admin: verify rest payment (pago final)
    @PutMapping("/{id}/verify-rest")
    public ResponseEntity<Order> verifyRestPayment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Order order = consolidadoService.verifyRestPayment(id, body.get("yapeReference"));
        return ResponseEntity.ok(tagAttended(order));
    }

    // Admin: verify Yape payment (legacy/generic)
    @PutMapping("/{id}/verify")
    public ResponseEntity<Order> verifyPayment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Order order = consolidadoService.verifyPayment(id, body.get("yapeReference"));
        return ResponseEntity.ok(tagAttended(order));
    }

    // Admin: reject payment
    @PutMapping("/{id}/reject")
    public ResponseEntity<Order> rejectPayment(@PathVariable Long id) {
        Order order = consolidadoService.rejectPayment(id);
        return ResponseEntity.ok(tagAttended(order));
    }

    // Admin: picking — marcar un ítem del pedido como verificado/comprado
    @PutMapping("/item/{itemId}/picked")
    public ResponseEntity<?> setItemPicked(@PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        OrderItem item = orderItemRepo.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found: " + itemId));
        item.setPicked(Boolean.TRUE.equals(body.get("picked")));
        orderItemRepo.save(item);
        return ResponseEntity.ok(Map.of("id", itemId, "picked", item.isPicked()));
    }

    // Admin: picking agregado — marcar TODOS los ítems de un producto dentro de un consolidado
    @PutMapping("/consolidado/{consolidadoId}/pick-product/{productId}")
    public ResponseEntity<?> pickProductInConsolidado(@PathVariable Long consolidadoId,
                                                       @PathVariable Long productId,
                                                       @RequestBody Map<String, Object> body) {
        boolean picked = Boolean.TRUE.equals(body.get("picked"));
        List<OrderItem> items = orderItemRepo.findByProductInConsolidado(productId, consolidadoId);
        for (OrderItem it : items) it.setPicked(picked);
        orderItemRepo.saveAll(items);
        return ResponseEntity.ok(Map.of("updated", items.size(), "picked", picked));
    }

    // Public: client edits their own order
    @PutMapping("/edit-by-client")
    public ResponseEntity<?> editByClient(@Valid @RequestBody OrderRequest request) {
        try {
            if (request.getExistingOrderCode() == null || request.getExistingOrderCode().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Se requiere el código de pedido."));
            }
            Order order = consolidadoService.editOrderByClient(
                    request.getExistingOrderCode(),
                    request.getClientPhone(),
                    request.getItems());
            return ResponseEntity.ok(order);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "Error al editar el pedido."));
        }
    }

    // Admin: update client info (name, phone)
    @PutMapping("/{id}/update-client")
    public ResponseEntity<Order> updateClient(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found: " + id));
        if (body.containsKey("clientName")) order.setClientName(body.get("clientName"));
        if (body.containsKey("clientPhone")) order.setClientPhone(body.get("clientPhone"));
        return ResponseEntity.ok(tagAttended(order));
    }

    // Admin: delete rejected or separated order
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found: " + id));
        String status = order.getPaymentStatus();
        if (!"RECHAZADO".equals(status) && !"SEPARADO".equals(status)) {
            return ResponseEntity.badRequest().build();
        }
        Long consolidadoId = order.getConsolidado().getId();
        orderRepo.delete(order);
        consolidadoService.recalculateConsolidado(consolidadoId);
        return ResponseEntity.ok().build();
    }
}