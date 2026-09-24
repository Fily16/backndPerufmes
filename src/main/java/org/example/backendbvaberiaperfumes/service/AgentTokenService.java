package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.config.JwtUtil;
import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tokens de agente: el MCP local de Claude entra al panel con un token largo (1 anio) en vez de guardar
 * la clave de la duena. Se emiten desde la pantalla de login de siempre (tras iniciar sesion normal) y se
 * cortan de golpe con "Desconectar Claude", sin tocar las sesiones normales del panel.
 *
 * La revocacion NO usa la hora: cada token lleva su "generacion" y desconectar sube el contador, asi que
 * los anteriores dejan de valer al instante y uno nuevo emitido el mismo segundo sigue siendo valido
 * (el "iat" del JWT solo tiene precision de segundos y eso hacia fallar la comparacion por fecha).
 * La generacion se cachea 30 s porque el filtro JWT la consulta en cada peticion.
 */
@Service
public class AgentTokenService {

    public static final String GENERATION_KEY = "agent_tokens_generation";
    public static final String REVOKED_AT_KEY = "agent_tokens_revoked_at";
    public static final int DEFAULT_DAYS = 365;

    private final AppConfigRepository configRepo;
    private final JwtUtil jwtUtil;

    private volatile long cachedGeneration = -1;
    private volatile long cachedUntil = 0;

    public AgentTokenService(AppConfigRepository configRepo, JwtUtil jwtUtil) {
        this.configRepo = configRepo;
        this.jwtUtil = jwtUtil;
    }

    /** Emite un token de agente para el admin que ya inicio sesion. */
    public Map<String, Object> issue(String email) {
        String token = jwtUtil.generateAgentToken(email, DEFAULT_DAYS, generation());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("email", email);
        out.put("expiresAt", jwtUtil.getExpiration(token).toInstant().toString());
        out.put("days", DEFAULT_DAYS);
        out.put("generation", generation());
        return out;
    }

    /** "Desconectar Claude": sube la generacion y todos los tokens de agente anteriores dejan de valer. */
    public Map<String, Object> revokeAll() {
        long next = generation() + 1;
        save(GENERATION_KEY, String.valueOf(next), "Generacion de tokens de agente (Desconectar Claude la sube)");
        save(REVOKED_AT_KEY, String.valueOf(System.currentTimeMillis()), "Ultima vez que se desconecto a Claude");
        cachedGeneration = next;
        cachedUntil = System.currentTimeMillis() + 30_000;
        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generation", generation());
        Long revoked = readLong(REVOKED_AT_KEY);
        out.put("revokedAt", revoked != null && revoked > 0 ? Instant.ofEpochMilli(revoked).toString() : null);
        out.put("days", DEFAULT_DAYS);
        return out;
    }

    /** true si el token es de agente y pertenece a una generacion anterior a la vigente. */
    public boolean isRevoked(String token) {
        if (!jwtUtil.isAgentToken(token)) return false;
        long gen = jwtUtil.getAgentGeneration(token);
        return gen < generation();
    }

    private long generation() {
        long now = System.currentTimeMillis();
        if (cachedGeneration >= 0 && now < cachedUntil) return cachedGeneration;
        Long v = readLong(GENERATION_KEY);
        cachedGeneration = v == null ? 0 : v;
        cachedUntil = now + 30_000;
        return cachedGeneration;
    }

    private Long readLong(String key) {
        return configRepo.findByConfigKey(key).map(AppConfig::getConfigValue).map(v -> {
            try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return null; }
        }).orElse(null);
    }

    private void save(String key, String value, String description) {
        AppConfig c = configRepo.findByConfigKey(key).orElseGet(() -> {
            AppConfig n = new AppConfig();
            n.setConfigKey(key);
            n.setDescription(description);
            return n;
        });
        c.setConfigValue(value);
        configRepo.save(c);
    }
}
