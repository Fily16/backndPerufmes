package org.example.backendbvaberiaperfumes.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final org.springframework.beans.factory.ObjectProvider<
            org.example.backendbvaberiaperfumes.service.AgentTokenService> agentTokens;

    public JwtFilter(JwtUtil jwtUtil,
                     org.springframework.beans.factory.ObjectProvider<
                             org.example.backendbvaberiaperfumes.service.AgentTokenService> agentTokens) {
        this.jwtUtil = jwtUtil;
        this.agentTokens = agentTokens; // perezoso: el filtro se crea antes que los servicios
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            // Token de agente (MCP) revocado desde "Desconectar Claude": se ignora aunque siga vigente.
            var svc = agentTokens.getIfAvailable();
            boolean revoked = svc != null && svc.isRevoked(token);
            if (!revoked && jwtUtil.validateToken(token)) {
                String email = jwtUtil.getEmailFromToken(token);
                var auth = new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        chain.doFilter(request, response);
    }
}
