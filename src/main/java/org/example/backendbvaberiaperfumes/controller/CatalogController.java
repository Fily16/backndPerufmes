package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogController {

    private final ProductService productService;

    public CatalogController(ProductService productService) {
        this.productService = productService;
    }

    /** Cutover: archiva el catalogo viejo (productos sin ofertas de proveedor). No borra nada. */
    @PostMapping("/archive-legacy")
    public ResponseEntity<Map<String, Object>> archiveLegacy() {
        int n = productService.archiveLegacy();
        return ResponseEntity.ok(Map.of(
                "archived", n,
                "message", "Catalogo viejo archivado: " + n + " productos (no se borraron)."));
    }
}
