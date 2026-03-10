package org.example.backendbvaberiaperfumes.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**") // Aplica a todos los endpoints de tu API (/api/auth, /api/products, etc.)
                        .allowedOrigins("https://aroma-studio-8bjblrkil-barbers-projects-dad7ecf6.vercel.app") // La URL exacta de tu frontend
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Permite estos métodos (OPTIONS es el preflight request que fallaba)
                        .allowedHeaders("*") // Permite cualquier cabecera
                        .allowCredentials(true); // Necesario si manejas cookies o tokens de autenticación
            }
        };
    }
}