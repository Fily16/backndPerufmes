package org.example.backendbvaberiaperfumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifica la capa web end-to-end (no solo servicios): login JWT, subida multipart,
 * seguridad de /api/admin, catalogo publico y serializacion JSON de la asignacion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class WebLayerImportTest {

    @Autowired MockMvc mvc;
    @Autowired SupplierRepository supplierRepo;
    @Autowired ObjectMapper om;

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test
    void flujoWebCompleto() throws Exception {
        // 1. login admin -> token
        String loginBody = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@aromastudio.pe\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = om.readTree(loginBody).get("token").asText();
        assertNotNull(token);

        Long zid = supplierRepo.findByName("Zimaxx").map(Supplier::getId).orElseThrow();
        byte[] zimaxx = Files.readAllBytes(Path.of("Zimaxx.xlsx"));

        // 2. importar SIN token -> bloqueado por seguridad
        mvc.perform(multipart("/api/admin/suppliers/" + zid + "/import")
                        .file(new MockMultipartFile("file", "Zimaxx.xlsx", XLSX, zimaxx)))
                .andExpect(status().is4xxClientError());

        // 3. importar CON token -> 200 + resumen
        String sumBody = mvc.perform(multipart("/api/admin/suppliers/" + zid + "/import")
                        .file(new MockMultipartFile("file", "Zimaxx.xlsx", XLSX, zimaxx))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sum = om.readTree(sumBody);
        assertEquals(985, sum.get("rowsRead").asInt());
        assertEquals(985, sum.get("offersCreated").asInt());

        // 4. catalogo publico (sin auth) con inStockOnly -> solo los importados con stock.
        // 984 y no 985: el "Mini Moschino 0.17 Oz" a $3 cae en la cuarentena de precios
        // (min_plausible_cost_usd=4) y queda fuera de stock hasta aprobarlo en el preview.
        String prods = mvc.perform(get("/api/products").param("inStockOnly", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(984, om.readTree(prods).size(), "984 en stock (1 sospechosa en cuarentena de precio)");

        // 5. asignacion (admin) -> 200 y JSON valido
        String activeBody = mvc.perform(get("/api/consolidados/active"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long consId = om.readTree(activeBody).get("id").asLong();
        mvc.perform(get("/api/admin/consolidados/" + consId + "/allocation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consolidadoId").value(consId));

        // 6. cutover archive-legacy (admin) -> archiva los productos del seed que quedan SIN ofertas.
        // 63 y no 86: productos del seed sin UPC que se UNIFICARON por nombre con filas de Zimaxx
        // (adopcion de codigo en el import). Bajo de 69 a 63 al dejar de tratar "unisex vs men/women"
        // como productos distintos: es etiquetado inconsistente entre proveedores, no contradiccion.
        mvc.perform(post("/api/admin/catalog/archive-legacy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(63));
    }
}
