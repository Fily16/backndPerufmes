package org.example.backendbvaberiaperfumes.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final SecretKey key;
    private final long expirationMs;
    private final boolean ephemeralSecret;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expiration-ms}") long expirationMs,
                   @Value("${spring.datasource.url:}") String datasourceUrl) {
        // El secreto de ejemplo esta en el repo PUBLICO: con el, cualquiera firmaria un token de admin.
        // Si produccion (BD que no es H2) no define JWT_SECRET, se usa una clave aleatoria por arranque:
        // los tokens falsificados son imposibles y el unico efecto es volver a iniciar sesion tras un reinicio.
        // En local/tests (H2) se mantiene el secreto configurado para que las sesiones sobrevivan reinicios.
        this.ephemeralSecret = DefaultSecretsWarning.isRemoteDatabase(datasourceUrl) && isDefaultOrBlank(secret);
        if (ephemeralSecret) {
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
            log.warn("[SEGURIDAD] JWT_SECRET no esta definido en produccion: se genero una clave aleatoria para este "
                    + "arranque (las sesiones del panel se cierran al reiniciar). Define JWT_SECRET en Render.");
        } else {
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.expirationMs = expirationMs;
    }

    static boolean isDefaultOrBlank(String secret) {
        return secret == null || secret.isBlank() || DefaultSecretsWarning.DEFAULT_JWT_SECRET.equals(secret.trim());
    }

    /** true si produccion arranco sin JWT_SECRET propio y se usa una clave aleatoria de este arranque. */
    public boolean isEphemeralSecret() {
        return ephemeralSecret;
    }

    public String generateToken(String email) {
        return Jwts.builder()
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Token largo para un agente (el MCP local de Claude): mismo poder que la admin, pero marcado con
     * el claim "agent" y revocable en bloque desde el panel (ver AgentTokenService: todos los emitidos
     * antes de la fecha de revocacion dejan de valer).
     */
    public String generateAgentToken(String email, int days, long generation) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .claim("agent", true)
                .claim("gen", generation)
                .issuedAt(new Date(now))
                .expiration(new Date(now + days * 24L * 60 * 60 * 1000))
                .signWith(key)
                .compact();
    }

    /** Generacion del token de agente (-1 si no es de agente o no se puede leer). */
    public long getAgentGeneration(String token) {
        try {
            Number n = getClaims(token).get("gen", Number.class);
            return n == null ? 0 : n.longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /** true si el token es de agente (MCP), no una sesion normal del panel. */
    public boolean isAgentToken(String token) {
        try {
            return Boolean.TRUE.equals(getClaims(token).get("agent", Boolean.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** Momento de emision en milisegundos, o 0 si no se puede leer. */
    public long getIssuedAtMs(String token) {
        try {
            Date d = getClaims(token).getIssuedAt();
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public Date getExpiration(String token) {
        return getClaims(token).getExpiration();
    }

    public String getEmailFromToken(String token) {
        return getClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
