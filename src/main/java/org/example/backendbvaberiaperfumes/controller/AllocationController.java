package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.model.PurchasePlan;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AllocationController {

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    /** Que comprar a cada proveedor (advisory, no persiste). Endpoint historico. */
    @GetMapping("/consolidados/{id}/allocation")
    public ResponseEntity<AllocationResponse> getAllocation(@PathVariable Long id) {
        return ResponseEntity.ok(allocationService.computeAllocation(id));
    }

    /** Calcula y guarda un plan DRAFT (reemplaza drafts anteriores). Devuelve planId. */
    @PostMapping("/consolidados/{id}/allocation/compute")
    public ResponseEntity<AllocationResponse> compute(@PathVariable Long id) {
        return ResponseEntity.ok(allocationService.computeAndSaveDraft(id));
    }

    /**
     * Confirma el plan: desde aqui la ganancia del consolidado se calcula contra el
     * costo REAL decidido. Si hay lineas bajo el piso de margen y force=false -> 409.
     */
    @PostMapping("/consolidados/{id}/allocation/{planId}/confirm")
    public ResponseEntity<?> confirm(@PathVariable Long id, @PathVariable Long planId,
                                     @RequestParam(defaultValue = "false") boolean force) {
        try {
            PurchasePlan plan = allocationService.confirmPlan(id, planId, force);
            return ResponseEntity.ok(plan);
        } catch (AllocationService.MarginFloorException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(),
                    "marginWarnings", e.warnings));
        }
    }

    /** Plan vigente (CONFIRMED, o el ultimo DRAFT si no hay confirmado). */
    @GetMapping("/consolidados/{id}/allocation/plan")
    public ResponseEntity<?> currentPlan(@PathVariable Long id) {
        return allocationService.currentPlan(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("plan", "NONE")));
    }

    /** Margen por producto: ingreso ya cobrado vs costo (plan confirmado o base actual). */
    @GetMapping("/consolidados/{id}/margin-report")
    public ResponseEntity<List<Map<String, Object>>> marginReport(@PathVariable Long id) {
        return ResponseEntity.ok(allocationService.marginReport(id));
    }
}
