package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.OrderRequest;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.repository.OrderRepository;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final ConsolidadoService consolidadoService;
    private final OrderRepository orderRepo;

    public OrderController(ConsolidadoService consolidadoService, OrderRepository orderRepo) {
        this.consolidadoService = consolidadoService;
        this.orderRepo = orderRepo;
    }

    // Public: client creates an order
    @PostMapping
    public ResponseEntity<?> createOrder(@Valid @RequestBody OrderRequest request) {
        try {
            Order order = consolidadoService.createOrder(request);
            return ResponseEntity.ok(order);
        } catch (IllegalArgumentException e) {
            // Si hay un error (ej. el código no existe o el celular no coincide), devolvemos un 400 amigable
            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            // Error general del servidor
            return ResponseEntity.status(500).body(Map.of("message", "Error interno al procesar el pedido."));
        }
    }

    // Admin: get all orders
    @GetMapping
    public List<Order> getAll(@RequestParam(required = false) String status) {
        if (status != null) return orderRepo.findByPaymentStatus(status);
        return orderRepo.findAll();
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
        return ResponseEntity.ok(order);
    }

    // Admin: verify rest payment (pago final)
    @PutMapping("/{id}/verify-rest")
    public ResponseEntity<Order> verifyRestPayment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Order order = consolidadoService.verifyRestPayment(id, body.get("yapeReference"));
        return ResponseEntity.ok(order);
    }

    // Admin: verify Yape payment (legacy/generic)
    @PutMapping("/{id}/verify")
    public ResponseEntity<Order> verifyPayment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Order order = consolidadoService.verifyPayment(id, body.get("yapeReference"));
        return ResponseEntity.ok(order);
    }

    // Admin: reject payment
    @PutMapping("/{id}/reject")
    public ResponseEntity<Order> rejectPayment(@PathVariable Long id) {
        Order order = consolidadoService.rejectPayment(id);
        return ResponseEntity.ok(order);
    }
}