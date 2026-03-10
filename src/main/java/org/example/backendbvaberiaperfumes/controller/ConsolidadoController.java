package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.FullBreakdownResponse;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.service.ConsolidadoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/consolidados")
public class ConsolidadoController {

    private final ConsolidadoService consolidadoService;

    public ConsolidadoController(ConsolidadoService consolidadoService) {
        this.consolidadoService = consolidadoService;
    }

    // Public: get active consolidado info
    @GetMapping("/active")
    public ResponseEntity<Consolidado> getActive() {
        Consolidado active = consolidadoService.getOrCreateActive();
        return ResponseEntity.ok(active);
    }

    // Admin: list all consolidados
    @GetMapping
    public List<Consolidado> getAll() {
        return consolidadoService.getAll();
    }

    // Admin: get consolidado details
    @GetMapping("/{id}")
    public ResponseEntity<Consolidado> getById(@PathVariable Long id) {
        return ResponseEntity.ok(consolidadoService.getById(id));
    }

    // Admin: get orders of a consolidado
    @GetMapping("/{id}/orders")
    public List<Order> getOrders(@PathVariable Long id) {
        return consolidadoService.getOrdersByConsolidado(id);
    }

    // Admin: close consolidado
    @PutMapping("/{id}/close")
    public ResponseEntity<Consolidado> close(@PathVariable Long id) {
        return ResponseEntity.ok(consolidadoService.closeConsolidado(id));
    }

    // Admin: get full breakdown (consolidado + miCompra + total)
    @GetMapping("/{id}/full-breakdown")
    public ResponseEntity<FullBreakdownResponse> getFullBreakdown(@PathVariable Long id) {
        return ResponseEntity.ok(consolidadoService.getFullBreakdown(id));
    }

    // Admin: create new consolidado
    @PostMapping
    public ResponseEntity<Consolidado> createNew() {
        Consolidado c = consolidadoService.getOrCreateActive();
        return ResponseEntity.ok(c);
    }
}
