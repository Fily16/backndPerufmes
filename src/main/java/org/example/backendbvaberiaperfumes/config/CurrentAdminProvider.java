package org.example.backendbvaberiaperfumes.config;

import org.example.backendbvaberiaperfumes.model.Admin;
import org.example.backendbvaberiaperfumes.repository.AdminRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resuelve el Admin autenticado a partir del JWT (el JwtFilter pone el email como principal).
 * Se usa para etiquetar qué vendedor gestionó cada pedido (ERP multiusuario).
 */
@Component
public class CurrentAdminProvider {

    private final AdminRepository adminRepo;

    public CurrentAdminProvider(AdminRepository adminRepo) {
        this.adminRepo = adminRepo;
    }

    /** Admin actual o null si no hay sesión válida. */
    public Admin current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return adminRepo.findByEmail(auth.getName()).orElse(null);
    }
}
