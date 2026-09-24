package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Token de agente (MCP local de Claude): se emite tras iniciar sesion, dura un anio, sirve para todo
 * /api/admin/** y se puede cortar desde "Desconectar Claude" sin tocar las sesiones normales del panel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agenttokentest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url=",
        "spring.mail.password=",
        "resend.api.key="
})
class AgentTokenTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String sessionToken() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@aromastudio.pe\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("token").asText();
    }

    private int statusOf(String token) throws Exception {
        return mvc.perform(get("/api/admin/nso/summary").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void conectarDesconectarYReconectar() throws Exception {
        // Sin sesion no se puede pedir el token de agente
        assertEquals(403, mvc.perform(post("/api/admin/agent-tokens")).andReturn().getResponse().getStatus());

        String session = sessionToken();
        String created = mvc.perform(post("/api/admin/agent-tokens").header("Authorization", "Bearer " + session))
                .andReturn().getResponse().getContentAsString();
        var json = om.readTree(created);
        String agent = json.get("token").asText();
        assertEquals("admin@aromastudio.pe", json.get("email").asText());
        assertEquals(365, json.get("days").asInt());
        // Dura ~1 anio: bastante mas que la sesion normal (1 dia)
        Instant exp = Instant.parse(json.get("expiresAt").asText());
        assertTrue(exp.isAfter(Instant.now().plusSeconds(300L * 24 * 3600)), "deberia durar casi un anio: " + exp);

        // El token de agente abre el panel igual que la sesion
        assertEquals(200, statusOf(agent));

        // "Desconectar Claude": el de agente deja de valer, la sesion normal sigue
        mvc.perform(delete("/api/admin/agent-tokens").header("Authorization", "Bearer " + session))
                .andExpect(r -> assertEquals(200, r.getResponse().getStatus()));
        assertEquals(403, statusOf(agent), "el token de agente revocado ya no debe entrar");
        assertEquals(200, statusOf(session), "la sesion normal del panel no se toca");

        // Reconectar: un token nuevo (emitido despues de la revocacion) SI vale
        String again = om.readTree(mvc.perform(post("/api/admin/agent-tokens")
                        .header("Authorization", "Bearer " + session))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        assertEquals(200, statusOf(again));
    }
}
