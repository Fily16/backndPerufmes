package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AllocationController {

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    /** Que comprar a cada proveedor para este consolidado. */
    @GetMapping("/consolidados/{id}/allocation")
    public ResponseEntity<AllocationResponse> getAllocation(@PathVariable Long id) {
        return ResponseEntity.ok(allocationService.computeAllocation(id));
    }
}
