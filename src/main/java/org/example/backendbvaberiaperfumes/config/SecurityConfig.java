package org.example.backendbvaberiaperfumes.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
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
                        .requestMatchers("/h2-console/**").permitAll()
                        // El resto de /api/consolidados (lista con ganancias, pedidos con datos
                        // de clientes) es SOLO admin: antes quedaba publico por el anyRequest().
                        .requestMatchers("/api/consolidados/**").authenticated()
                        // Admin endpoints
                        .requestMatchers("/api/admin/**").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(h -> h.frameOptions(f -> f.sameOrigin())); // For H2 console

        return http.build();
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