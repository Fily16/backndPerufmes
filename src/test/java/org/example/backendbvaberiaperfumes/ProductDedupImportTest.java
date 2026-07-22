package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.controller.MatchReviewController;
import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.dto.ParsedRow;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.repository.MatchCandidateRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Robustez de identidad de producto en el import: un perfume que primero entra SIN UPC y
 * luego llega CON un UPC valido debe UNIFICARSE (adoptar el codigo), no duplicarse; y si el
 * mismo nombre choca con otro producto que YA tiene un GTIN distinto, se envia a revision en
 * vez de fusionar solo. Ademas el guard del endpoint de edicion manual de UPC.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:deduptest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class ProductDedupImportTest {

    @Autowired ExcelImportService importService;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;
    @Autowired ProductRepository productRepo;
    @Autowired MatchCandidateRepository candidateRepo;
    @Autowired MatchReviewController reviewController;

    // GTINs validos DISTINTOS por test (el H2 se comparte entre metodos: reusar un codigo
    // haria que byGtin de un test encontrara el producto de otro).
    private static final String GTIN_A = "06290362342373"; // test 1 (adopcion)
    private static final String GTIN_C = "00085715167224"; // test 2 (existente)
    private static final String GTIN_D = "03614273955546"; // test 2 (nuevo, distinto)
    private static final String GTIN_B = "03614274143751"; // test 3 (guard)

    private ParsedRow row(String brand, String name, Integer ml, String rawTitle, String rawUpc) {
        ParsedRow pr = new ParsedRow();
        pr.brand = brand;
        pr.name = name;
        pr.ml = ml;
        pr.rawTitle = rawTitle;
        pr.costUsd = 20.0;
        pr.inStock = true;
        if (rawUpc != null) {
            GtinCanonicalizer.GtinResult gr = GtinCanonicalizer.canonicalize(rawUpc);
            pr.gtin = gr.canonical14;
            pr.gtinRaw = gr.rawDigits;
            pr.gtinStatus = gr.status.name();
            assertNotNull(pr.gtin, "GTIN de prueba invalido: " + rawUpc);
        }
        return pr;
    }

    @Test
    void productoSinUpcQueLuegoRecibeUpcSeUnificaNoSeDuplica() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();

        // 1) Entra SIN UPC -> crea el producto canonico.
        ImportSummary s1 = importService.commit(zimaxx, List.of(
                row("Dedupa", "Fantasma Unico", 100, "Dedupa Fantasma Unico 100ml", null)));
        assertEquals(1, s1.getProductsCreated(), "primer import crea el producto");

        List<Product> before = productRepo.findByArchivedFalse().stream()
                .filter(p -> "Dedupa".equalsIgnoreCase(p.getBrand())).toList();
        assertEquals(1, before.size());
        assertTrue(before.get(0).getGtin() == null || before.get(0).getGtin().isBlank(),
                "el producto entro sin UPC");
        Long xId = before.get(0).getId();

        // 2) El MISMO perfume llega ahora CON un UPC valido -> debe ADOPTAR el codigo, no duplicar.
        ImportSummary s2 = importService.commit(zimaxx, List.of(
                row("Dedupa", "Fantasma Unico", 100, "Dedupa Fantasma Unico 100ml", GTIN_A)));

        assertEquals(1, s2.getGtinAdopted(), "el producto sin UPC adopto el codigo entrante");
        assertEquals(0, s2.getProductsCreated(), "no se crea un producto nuevo");

        List<Product> after = productRepo.findByArchivedFalse().stream()
                .filter(p -> "Dedupa".equalsIgnoreCase(p.getBrand())).toList();
        assertEquals(1, after.size(), "sigue habiendo UN solo producto canonico (no duplicado)");
        Product x = after.get(0);
        assertEquals(xId, x.getId(), "es el mismo producto, no uno nuevo");
        assertEquals(GtinCanonicalizer.canonicalize(GTIN_A).canonical14, x.getGtin(), "adopto el UPC");

        // Y ahora SI tiene oferta en stock (lo que "Ver que comprar" necesita).
        assertFalse(offerRepo.findByProduct_IdAndInStockTrue(x.getId()).isEmpty(),
                "el producto unificado tiene una oferta en stock");
    }

    @Test
    void mismoNombreConGtinDistintoVaARevisionNoSeFusionaSolo() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        long pendingBefore = candidateRepo.countByStatus("PENDING");

        // Producto existente CON un GTIN.
        importService.commit(zimaxx, List.of(
                row("Choca", "Gemelo Riesgo", 100, "Choca Gemelo Riesgo 100ml", GTIN_C)));

        // Mismo nombre exacto pero con OTRO GTIN valido -> NO adoptar; crear + encolar a revision.
        ImportSummary s2 = importService.commit(zimaxx, List.of(
                row("Choca", "Gemelo Riesgo", 100, "Choca Gemelo Riesgo 100ml", GTIN_D)));

        assertEquals(0, s2.getGtinAdopted(), "no se adopta: el gemelo ya tiene un GTIN distinto");
        assertEquals(1, s2.getProductsCreated(), "se crea el producto (posible duplicado) marcado para revision");
        assertTrue(candidateRepo.countByStatus("PENDING") > pendingBefore,
                "se encolo un candidato de revision para que el admin decida");
    }

    @Test
    void guardDeUpcManualRechazaCodigoQueYaEsDeOtroProducto() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();

        // Producto A con GTIN_B; producto B sin UPC.
        importService.commit(zimaxx, List.of(
                row("Guarda", "Dueno del Codigo", 100, "Guarda Dueno del Codigo 100ml", GTIN_B)));
        importService.commit(zimaxx, List.of(
                row("Guarda", "Otro Distinto", 50, "Guarda Otro Distinto 50ml", null)));

        Product owner = productRepo.findByArchivedFalse().stream()
                .filter(p -> "Guarda".equalsIgnoreCase(p.getBrand()) && "Dueno del Codigo".equalsIgnoreCase(p.getName()))
                .findFirst().orElseThrow();
        Product other = productRepo.findByArchivedFalse().stream()
                .filter(p -> "Guarda".equalsIgnoreCase(p.getBrand()) && "Otro Distinto".equalsIgnoreCase(p.getName()))
                .findFirst().orElseThrow();

        // Intentar asignar a "other" el UPC que ya es de "owner" -> 409 con el conflicto.
        ResponseEntity<?> resp = reviewController.setGtin(other.getId(), java.util.Map.of("gtin", GTIN_B));
        assertEquals(409, resp.getStatusCode().value(), "el guard bloquea el UPC ya usado por otro producto");

        // El producto NO tomo el codigo.
        Product otherReloaded = productRepo.findById(other.getId()).orElseThrow();
        assertTrue(otherReloaded.getGtin() == null || otherReloaded.getGtin().isBlank(),
                "no se pisa el UPC en conflicto");
    }
}
