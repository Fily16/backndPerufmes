package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.ConsolidadoPublicDTO;
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

    /**
     * Publico: estado del consolidado para el aviso/countdown de la tienda.
     * Solo lectura (nunca crea) y con fechas en epoch millis + hora del servidor.
     */
    @GetMapping("/current")
    public ResponseEntity<ConsolidadoPublicDTO> getCurrent() {
        return ResponseEntity.ok(consolidadoService.getCurrentPublic());
    }

    // Public: get active consolidado info (solo lectura; antes CREABA en cada visita anonima)
    @GetMapping("/active")
    public ResponseEntity<Consolidado> getActive() {
        Consolidado active = consolidadoService.getActiveOrNull();
        return active != null ? ResponseEntity.ok(active) : ResponseEntity.notFound().build();
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

    // El POST publico de creacion se elimino: los consolidados se abren explicitamente
    // desde POST /api/admin/consolidados/open (con fechas, titulo e imagen).

    // Admin: delete consolidado (only non-ABIERTO)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConsolidado(@PathVariable Long id) {
        consolidadoService.deleteConsolidado(id);
        return ResponseEntity.ok().build();
    }
}
