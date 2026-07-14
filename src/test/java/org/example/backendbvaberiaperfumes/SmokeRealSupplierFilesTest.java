package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.MatchCandidateRepository;
import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
import org.example.backendbvaberiaperfumes.util.GtinCanonicalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HUMO con los Excel REALES de provedores/: FragranceSense (formato sucio) y
 * Zimaxx "US Wholesale - 2K". Verifica el criterio de aceptacion del plan:
 * idempotencia total, cero productos desde codigos con checksum invalido,
 * y los UPC compartidos colgando de UN producto con DOS ofertas.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:smoketest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class SmokeRealSupplierFilesTest {

    @Autowired ExcelImportService importService;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;
    @Autowired ProductRepository productRepo;
    @Autowired MatchCandidateRepository candidateRepo;

    private static final String FS_FILE = "provedores/FRAGSENSE LLC PRICE LIST 07-06-2026.... stock ..  (1).xlsx";
    private static final String ZX_FILE = "provedores/US Wholesale - 2K - Available. (5).xlsx";

    @Test
    void importaLosExcelRealesDosVeces() throws Exception {
        Long zimaxxId = supplierRepo.findByName("Zimaxx").map(Supplier::getId).orElseThrow();
        Supplier fs = supplierRepo.save(new Supplier("FragranceSense", 0.0, false));

        // ============ Primera pasada ============
        ImportSummary z1;
        try (FileInputStream is = new FileInputStream(new File(ZX_FILE))) {
            z1 = importService.importExcel(zimaxxId, is);
        }
        System.out.println("ZX1 -> filas=" + z1.getRowsRead() + " creados=" + z1.getProductsCreated()
                + " ofertas=" + z1.getOffersCreated() + " sospechosas=" + z1.getSuspiciousRows());

        ImportSummary f1;
        try (FileInputStream is = new FileInputStream(new File(FS_FILE))) {
            f1 = importService.importExcel(fs.getId(), is);
        }
        System.out.println("FS1 -> filas=" + f1.getRowsRead() + " creados=" + f1.getProductsCreated()
                + " ofertas=" + f1.getOffersCreated() + " sinUPC=" + f1.getNoUpcRows()
                + " l2Auto=" + f1.getL2AutoMatched() + " revision=" + f1.getReviewQueued()
                + " sospechosas=" + f1.getSuspiciousRows());

        // 979/617: el parser salta filas sin precio (una de Zimaxx y tres de FS).
        assertEquals(979, z1.getRowsRead(), "Zimaxx US Wholesale trae 979 filas con precio");
        assertEquals(617, f1.getRowsRead(), "FragranceSense trae 617 filas con precio");

        // FS: 12 typos de checksum + vacios -> filas sin UPC valido; ninguna crea identidad por codigo.
        assertTrue(f1.getNoUpcRows() >= 12, "los 12 typos de checksum de FS quedan sin UPC");
        // El cruce por UPC + matching por nombre evita duplicar los compartidos.
        assertTrue(f1.getL2AutoMatched() + f1.getReviewQueued() > 0, "el L2 debe trabajar en FS");

        // Ningun producto del catalogo quedo con un GTIN invalido.
        for (Product p : productRepo.findAll()) {
            if (p.getGtin() != null && !p.getGtin().isBlank()) {
                assertTrue(GtinCanonicalizer.checksumOk(p.getGtin()),
                        "GTIN invalido en catalogo: " + p.getGtin() + " (" + p.getName() + ")");
            }
        }

        // Productos compartidos: 1 producto con ofertas de ambos proveedores (~90 por UPC).
        Map<Long, java.util.Set<Long>> suppliersPerProduct = new HashMap<>();
        for (SupplierOffer o : offerRepo.findAll()) {
            suppliersPerProduct.computeIfAbsent(o.getProduct().getId(), k -> new java.util.HashSet<>())
                    .add(o.getSupplierId());
        }
        long shared = suppliersPerProduct.values().stream().filter(s -> s.size() >= 2).count();
        System.out.println("Productos compartidos (1 producto, 2+ ofertas): " + shared);
        assertTrue(shared >= 85, "al menos ~90 UPCs compartidos deben converger en un solo producto: " + shared);

        long pending = candidateRepo.countByStatus("PENDING");
        System.out.println("Candidatos de revision pendientes: " + pending);

        // ============ Segunda pasada: IDEMPOTENCIA total ============
        ImportSummary z2;
        try (FileInputStream is = new FileInputStream(new File(ZX_FILE))) {
            z2 = importService.importExcel(zimaxxId, is);
        }
        ImportSummary f2;
        try (FileInputStream is = new FileInputStream(new File(FS_FILE))) {
            f2 = importService.importExcel(fs.getId(), is);
        }
        assertEquals(0, z2.getProductsCreated(), "reimportar Zimaxx no crea productos");
        assertEquals(0, z2.getOffersCreated(), "reimportar Zimaxx no crea ofertas");
        assertEquals(0, f2.getProductsCreated(), "reimportar FS no crea productos");
        assertEquals(0, f2.getOffersCreated(), "reimportar FS no crea ofertas");
        assertEquals(pending, candidateRepo.countByStatus("PENDING"),
                "la cola de revision no crece al reimportar");
    }
}
