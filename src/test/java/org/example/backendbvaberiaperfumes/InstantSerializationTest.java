package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El frontend hace new Date(c.endsAt) y espera un ISO string.
 * Este test fija ese contrato: si alguien activa write-dates-as-timestamps,
 * el admin dejaria de leer las fechas y este test lo detecta.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:jsontest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class InstantSerializationTest {

    @Autowired ObjectMapper mapper;

    @Test
    void elConsolidadoSerializaFechasComoIsoString() throws Exception {
        Consolidado c = new Consolidado();
        c.setStatus("ABIERTO");
        c.setEndsAt(Instant.parse("2026-08-01T17:30:00Z"));
        String json = mapper.writeValueAsString(c);
        System.out.println("JSON -> " + json);
        assertTrue(json.contains("\"endsAt\":\"2026-08-01T17:30:00Z\""),
                "el frontend hace new Date(endsAt): debe ser ISO string, no epoch. JSON=" + json);
    }
}
