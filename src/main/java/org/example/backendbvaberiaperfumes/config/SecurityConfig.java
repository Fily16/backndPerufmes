package org.example.backendbvaberiaperfumes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    /**
     * Consola H2: APAGADA por defecto (H2_CONSOLE=true solo en la maquina local). El jar de produccion lleva el
     * driver H2 y la consola publica permitia conectarse a una BD H2 nueva y ejecutar SQL dentro del servidor.
     */
    private final boolean h2Console;

    public SecurityConfig(JwtFilter jwtFilter, @Value("${spring.h2.console.enabled:false}") boolean h2Console) {
        this.jwtFilter = jwtFilter;
        this.h2Console = h2Console;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Consola H2: solo si se encendio a proposito (H2_CONSOLE=true, local); si no, cerrada.
                        .requestMatchers("/h2-console/**").access(h2ConsoleAccess())
                        // Public endpoints
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/consolidados/active").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/consolidados/current").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/media/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/orders").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/orders/code/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/public").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/retail/stock").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/retail/form-sale").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/orders/edit-by-client").permitAll()
                        // El resto de /api/consolidados (lista con ganancias, pedidos con datos
                        // de clientes) es SOLO admin: antes quedaba publico por el anyRequest().
                        .requestMatchers("/api/consolidados/**").authenticated()
                        // Admin endpoints
                        .requestMatchers("/api/admin/**").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/**").authenticated()
                        // Cerrados en la Fase 0: antes quedaban publicos por el anyRequest().
                        // Crear productos es solo admin (el GET publico del catalogo sigue arriba).
                        .requestMatchers(HttpMethod.POST, "/api/products", "/api/products/**").authenticated()
                        // Lista y detalle de pedidos traen nombres y telefonos de clientes.
                        // /api/orders/code/** (consulta del cliente por codigo) ya es publico arriba:
                        // su matcher va ANTES y gana; "/api/orders/*" es un solo segmento.
                        .requestMatchers(HttpMethod.GET, "/api/orders", "/api/orders/*").authenticated()
                        // Inventario y ventas de tienda (cualquier metodo). /api/retail/stock y
                        // /api/retail/form-sale (Apps Script, con API key) siguen publicos arriba.
                        .requestMatchers("/api/retail/inventory", "/api/retail/inventory/**").authenticated()
                        .requestMatchers("/api/retail/sales", "/api/retail/sales/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // La consola H2 usa frames; sin ella se queda el DENY por defecto.
                .headers(h -> h.frameOptions(f -> {
                    if (h2Console) f.sameOrigin();
                    else f.deny();
                }));

        return http.build();
    }

    /** Consola H2 abierta solo con spring.h2.console.enabled=true (H2_CONSOLE=true); si no, 403 siempre. */
    private AuthorizationManager<RequestAuthorizationContext> h2ConsoleAccess() {
        AuthorizationDecision decision = new AuthorizationDecision(h2Console);
        return (authentication, context) -> decision;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // AQUÍ AGREGAMOS TUS DOMINIOS NUEVOS
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "https://fily16.github.io",
                "https://*.vercel.app",
                "https://www.aromastudiope.com",
                "https://aromastudiope.com"
        ));

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}