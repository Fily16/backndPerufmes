package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.config.CurrentAdminProvider;
import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.service.AgentTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Conexion de Claude (MCP local) con el panel. La duena entra por la pantalla de login de siempre y esta
 * ruta le entrega un token largo a la aplicacion local, en vez de guardar su clave en un archivo.
 * Todo cuelga de /api/admin/** asi que ya exige sesion iniciada.
 */
@RestController
@RequestMapping("/api/admin/agent-tokens")
public class AgentTokenController {

    private final AgentTokenService agentTokens;
    private final CurrentAdminProvider currentAdmin;

    public AgentTokenController(AgentTokenService agentTokens, CurrentAdminProvider currentAdmin) {
        this.agentTokens = agentTokens;
        this.currentAdmin = currentAdmin;
    }

    /** Emite un token de agente para la sesion actual (lo consume la pantalla de login al conectar Claude). */
    @PostMapping
    public ResponseEntity<?> create() {
        Admin admin = currentAdmin.current();
        if (admin == null) {
            return ResponseEntity.status(403).body(Map.of("message", "Inicia sesion para conectar Claude."));
        }
        return ResponseEntity.ok(agentTokens.issue(admin.getEmail()));
    }

    /** Estado: desde cuando estan revocados los tokens de agente. */
    @GetMapping
    public Map<String, Object> status() {
        return agentTokens.status();
    }

    /** "Desconectar Claude": corta todos los tokens de agente vigentes. No afecta al panel. */
    @DeleteMapping
    public Map<String, Object> revokeAll() {
        return agentTokens.revokeAll();
    }
}
