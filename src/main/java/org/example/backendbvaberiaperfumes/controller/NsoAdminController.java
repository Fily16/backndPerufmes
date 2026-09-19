package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.service.nso.NsoConflictException;
import org.example.backendbvaberiaperfumes.service.nso.NsoNotFoundException;
import org.example.backendbvaberiaperfumes.service.nso.NsoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;

/**
 * Panel NSO (Notificacion Sanitaria) — solo admin (JWT: /api/admin/** en SecurityConfig).
 * Rutas y JSON segun el contrato NSO; errores siempre {message} en espanol:
 * 400 datos invalidos, 404 no existe ({message, canCreate:true} si es un codigo que se puede agregar),
 * 409 choca con el estado actual.
 */
@RestController
@RequestMapping("/api/admin/nso")
public class NsoAdminController {

    private final NsoService nso;

    public NsoAdminController(NsoService nso) {
        this.nso = nso;
    }

    // 1. Subir la lista NSO (.xlsx o .csv)
    @PostMapping(value = "/catalog/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) return message(HttpStatus.BAD_REQUEST, "Adjunta la lista de NSO (.xlsx o .csv).");
        return run(() -> nso.uploadCatalog(file.getOriginalFilename(), file.getBytes()));
    }

    // 2. Resumen
    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        return run(nso::summary);
    }

    // 3. Encender/apagar el filtro publico
    @PutMapping("/gate")
    public ResponseEntity<?> gate(@RequestBody(required = false) Map<String, Object> body) {
        Object enabled = body == null ? null : body.get("enabled");
        if (!(enabled instanceof Boolean b)) {
            return message(HttpStatus.BAD_REQUEST, "Indica si el filtro va encendido o apagado (enabled: true/false).");
        }
        return run(() -> nso.setGate(b));
    }

    // 3b. Opciones (FASE 3): {acceptCanCodes?:boolean, reviewMinScore?:number 0.5..0.95} -> summary
    @PutMapping("/settings")
    public ResponseEntity<?> settings(@RequestBody(required = false) Map<String, Object> body) {
        Object accept = body == null ? null : body.get("acceptCanCodes");
        Object min = body == null ? null : body.get("reviewMinScore");
        if (accept != null && !(accept instanceof Boolean)) {
            return message(HttpStatus.BAD_REQUEST, "acceptCanCodes debe ser true o false.");
        }
        if (min != null && !(min instanceof Number)) {
            return message(HttpStatus.BAD_REQUEST, "reviewMinScore debe ser un número entre 0.5 y 0.95.");
        }
        Boolean acceptCanCodes = (Boolean) accept;
        Double reviewMinScore = min == null ? null : ((Number) min).doubleValue();
        return run(() -> nso.updateSettings(acceptCanCodes, reviewMinScore));
    }

    // 4. Volver a verificar: todo (202, en segundo plano) o solo unos productos (200, sincrono)
    @PostMapping("/rematch")
    public ResponseEntity<?> rematch(@RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("productIds");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Number n) ids.add(n.longValue());
                else return message(HttpStatus.BAD_REQUEST, "productIds debe ser una lista de números.");
            }
            return run(() -> Map.of("processed", nso.rematchProducts(ids).getProcessed()));
        }
        try {
            nso.startRematchAll();
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("started", true));
        } catch (Exception e) {
            return error(e);
        }
    }

    // 5. Indice compacto (solo productos con fila)
    @GetMapping("/index")
    public ResponseEntity<?> index() {
        return run(nso::index);
    }

    // 6. Productos con candidatos pendientes
    @GetMapping("/candidates/count")
    public ResponseEntity<?> candidatesCount() {
        return run(() -> Map.of("pending", nso.pendingCount()));
    }

    // 7. Cola de revision
    @GetMapping("/review")
    public ResponseEntity<?> review() {
        return run(nso::review);
    }

    // 8. "Es este"
    @PostMapping("/candidates/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable Long id) {
        return run(() -> nso.accept(id));
    }

    // 9. "Ninguno"
    @PostMapping("/products/{id}/reject-all")
    public ResponseEntity<?> rejectAll(@PathVariable Long id) {
        return run(() -> nso.rejectAll(id));
    }

    // 10. Asignar un codigo a mano
    @PostMapping("/products/{id}/assign")
    public ResponseEntity<?> assign(@PathVariable Long id, @RequestBody(required = false) NsoService.ManualCode body) {
        if (body == null || body.code == null || body.code.isBlank()) {
            return message(HttpStatus.BAD_REQUEST, "Escribe el código NSO (ej. NSOC70523-25PE).");
        }
        return run(() -> nso.assignManual(id, body));
    }

    // 11. Quitar el NSO
    @PostMapping("/products/{id}/unassign")
    public ResponseEntity<?> unassign(@PathVariable Long id) {
        return run(() -> nso.unassign(id));
    }

    // 12. Marca con NSO pero falta el producto
    @GetMapping("/brand-groups")
    public ResponseEntity<?> brandGroups() {
        return run(nso::brandGroups);
    }

    // 13. Productos por estado
    @GetMapping("/products")
    public ResponseEntity<?> products(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) return message(HttpStatus.BAD_REQUEST, "Indica el estado NSO (status).");
        return run(() -> nso.productsByStatus(status.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    // 14. Catalogo paginado
    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String brand,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return run(() -> nso.catalog(q, brand, page, size));
    }

    // 15. Agregar un codigo a mano (re-verifica esa marca al instante)
    @PostMapping(value = "/catalog", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addRecord(@RequestBody(required = false) NsoService.NewRecord body) {
        return run(() -> nso.addCatalogRecord(body));
    }

    // 16. Activar/desactivar un codigo (confirm=false solo previsualiza)
    @PutMapping("/catalog/{code}/active")
    public ResponseEntity<?> setActive(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        Object active = body == null ? null : body.get("active");
        if (!(active instanceof Boolean a)) {
            return message(HttpStatus.BAD_REQUEST, "Indica si el código queda activo (active: true/false).");
        }
        boolean confirm = body.get("confirm") instanceof Boolean c && c;
        return run(() -> nso.setCodeActive(code, a, confirm));
    }

    // 17. "Esta marca es la misma que..."
    @PostMapping("/brand-aliases")
    public ResponseEntity<?> brandAlias(@RequestBody(required = false) Map<String, Object> body) {
        String supplierBrand = body == null || body.get("supplierBrand") == null ? null : String.valueOf(body.get("supplierBrand"));
        String catalogBrandKey = body == null || body.get("catalogBrandKey") == null ? null : String.valueOf(body.get("catalogBrandKey"));
        return run(() -> nso.addBrandAlias(supplierBrand, catalogBrandKey));
    }

    // 18. Bitacora
    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestParam(required = false) Long productId,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(defaultValue = "200") int limit) {
        return run(() -> nso.events(productId, type, limit));
    }

    // ----------------------------------------------------------------------

    private ResponseEntity<?> run(Callable<?> action) {
        try {
            return ResponseEntity.ok(action.call());
        } catch (Exception e) {
            return error(e);
        }
    }

    static ResponseEntity<?> error(Exception e) {
        if (e instanceof NsoNotFoundException nf) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", nf.getMessage());
            if (nf.isCanCreate()) body.put("canCreate", true);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        if (e instanceof NoSuchElementException) {
            return message(HttpStatus.NOT_FOUND, e.getMessage() != null ? e.getMessage() : "No existe.");
        }
        if (e instanceof NsoConflictException) return message(HttpStatus.CONFLICT, e.getMessage());
        if (e instanceof IllegalArgumentException) return message(HttpStatus.BAD_REQUEST, e.getMessage());
        System.err.println("[NSO] Error inesperado: " + e);
        return message(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo completar la operación. No se guardaron cambios; intenta de nuevo.");
    }

    private static ResponseEntity<?> message(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
