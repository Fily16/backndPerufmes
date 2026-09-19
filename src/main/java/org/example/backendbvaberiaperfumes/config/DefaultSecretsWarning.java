package org.example.backendbvaberiaperfumes.config;

import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.repository.AdminRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aviso al arrancar (WARN en los logs de Render) si produccion usa los valores de ejemplo del repo: el secreto JWT
 * por defecto (cualquiera que lea application.properties podria firmar un token de admin) o las claves de ejemplo
 * de las cuentas admin. Solo AVISA: nunca impide arrancar (no sabemos que variables tiene Render y un push a master
 * despliega directo). Con H2 (maquina local / tests) no dice nada.
 */
@Component
public class DefaultSecretsWarning {

    private static final Logger log = LoggerFactory.getLogger(DefaultSecretsWarning.class);

    /** Valor por defecto de app.jwt.secret en application.properties (esta en el historial de git). */
    public static final String DEFAULT_JWT_SECRET = "aromastudio-secret-key-change-in-production-2024-very-long-key-at-least-256-bits";
    static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final String datasourceUrl;
    private final String jwtSecret;
    private final String adminEmail;
    private final AdminRepository adminRepo;
    private final PasswordEncoder encoder;

    public DefaultSecretsWarning(@Value("${spring.datasource.url:}") String datasourceUrl,
                                 @Value("${app.jwt.secret:}") String jwtSecret,
                                 @Value("${app.admin.email:}") String adminEmail,
                                 AdminRepository adminRepo, PasswordEncoder encoder) {
        this.datasourceUrl = datasourceUrl;
        this.jwtSecret = jwtSecret;
        this.adminEmail = adminEmail;
        this.adminRepo = adminRepo;
        this.encoder = encoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfDefaults() {
        try {
            if (!isRemoteDatabase(datasourceUrl)) return;
            boolean adminDefault = storedPasswordIs(adminEmail, DEFAULT_ADMIN_PASSWORD);
            for (String w : warnings(datasourceUrl, jwtSecret, adminDefault)) log.warn(w);
        } catch (RuntimeException e) {
            log.warn("[SEGURIDAD] No se pudo revisar si se usan claves por defecto: {}", e.toString());
        }
    }

    /** true si la BD NO es H2 (produccion: PostgreSQL en Aiven). */
    public static boolean isRemoteDatabase(String datasourceUrl) {
        return datasourceUrl != null && !datasourceUrl.isBlank()
                && !datasourceUrl.trim().toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");
    }

    /** Avisos a mostrar (vacio con H2 o si todo esta bien). Puro, para testearlo sin arrancar la app. */
    public static List<String> warnings(String datasourceUrl, String jwtSecret, boolean adminHasDefaultPassword) {
        List<String> out = new ArrayList<>();
        if (!isRemoteDatabase(datasourceUrl)) return out;
        if (jwtSecret == null || jwtSecret.isBlank() || DEFAULT_JWT_SECRET.equals(jwtSecret.trim())) {
            out.add("[SEGURIDAD] JWT_SECRET no esta definido: JwtUtil usa una clave aleatoria por arranque (no se pueden "
                    + "falsificar tokens, pero el panel pide iniciar sesion tras cada reinicio). Define JWT_SECRET en Render.");
        }
        if (adminHasDefaultPassword) {
            out.add("[SEGURIDAD] La cuenta admin principal sigue con la clave de ejemplo (admin123). Cambiala.");
        }
        return out;
    }

    private boolean storedPasswordIs(String email, String plain) {
        if (email == null || email.isBlank()) return false;
        Admin a = adminRepo.findByEmail(email.trim()).orElse(null);
        return a != null && a.getPassword() != null && encoder.matches(plain, a.getPassword());
    }
}
