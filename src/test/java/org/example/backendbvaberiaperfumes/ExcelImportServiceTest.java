package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.ImportSummary;
import org.example.backendbvaberiaperfumes.model.Supplier;
import org.example.backendbvaberiaperfumes.model.SupplierOffer;
import org.example.backendbvaberiaperfumes.repository.SupplierOfferRepository;
import org.example.backendbvaberiaperfumes.repository.SupplierRepository;
import org.example.backendbvaberiaperfumes.service.ExcelImportService;
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
 * Verifica el importador contra los 2 Excel reales (en la raiz del proyecto).
 * Usa H2 en memoria para no tocar la base de desarrollo.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:importtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class ExcelImportServiceTest {

    @Autowired ExcelImportService importService;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;

    @Test
    void importaAmbosExcelYDiferenciaDuplicados() throws Exception {
        Long zimaxxId = supplierRepo.findByName("Zimaxx").map(Supplier::getId).orElseThrow();
        Long magnetId = supplierRepo.findByName("Magnet").map(Supplier::getId).orElseThrow();

        ImportSummary z;
        try (FileInputStream is = new FileInputStream(new File("Zimaxx.xlsx"))) {
            z = importService.importExcel(zimaxxId, is);
        }
        System.out.println("ZIMAXX -> filas=" + z.getRowsRead() + " creados=" + z.getProductsCreated()
                + " ofertas=" + z.getOffersCreated() + " colisiones=" + z.getCollisions());

        ImportSummary m;
        try (FileInputStream is = new FileInputStream(new File("Magnet Fragance.xlsx"))) {
            m = importService.importExcel(magnetId, is);
        }
        System.out.println("MAGNET -> filas=" + m.getRowsRead() + " creados=" + m.getProductsCreated()
                + " ofertas=" + m.getOffersCreated() + " colisiones=" + m.getCollisions()
                + " sinUPC=" + m.getNoUpcRows());

        // Zimaxx: 985 filas, todas con UPC, sin colisiones internas
        assertEquals(985, z.getRowsRead(), "Zimaxx debe leer 985 productos");
        assertEquals(985, z.getOffersCreated(), "Zimaxx debe crear 985 ofertas");
        assertEquals(0, z.getCollisions(), "Zimaxx no tiene colisiones internas");

        // Magnet: 504 filas, 6 colisiones de UPC, 5 sin UPC valido
        // (2 celdas vacias + 3 codigos con checksum GS1 invalido = typos del proveedor
        //  que ahora quedan en cuarentena en vez de crear identidades falsas).
        assertEquals(504, m.getRowsRead(), "Magnet debe leer 504 productos");
        assertEquals(6, m.getCollisions(), "Magnet tiene 6 colisiones de UPC que NO se deben fusionar");
        assertEquals(5, m.getNoUpcRows(), "Magnet: 2 sin codigo + 3 typos de checksum en cuarentena");

        // Total de ofertas guardadas = 985 + 504
        assertEquals(1489, offerRepo.count(), "Deben quedar 1489 ofertas (985 Zimaxx + 504 Magnet)");

        // Productos con 2 ofertas = los 21 que venden ambos proveedores (match por UPC)
        Map<Long, Integer> offersPerProduct = new HashMap<>();
        for (SupplierOffer o : offerRepo.findAll()) {
            offersPerProduct.merge(o.getProduct().getId(), 1, Integer::sum);
        }
        long compartidos = offersPerProduct.values().stream().filter(c -> c >= 2).count();
        assertEquals(21, compartidos, "Deben existir 21 productos compartidos (1 producto, 2 ofertas)");

        // Idempotencia: reimportar Zimaxx no debe crear ofertas nuevas
        ImportSummary z2;
        try (FileInputStream is = new FileInputStream(new File("Zimaxx.xlsx"))) {
            z2 = importService.importExcel(zimaxxId, is);
        }
        assertEquals(0, z2.getOffersCreated(), "Reimportar no crea ofertas nuevas");
        assertEquals(985, z2.getOffersUpdated(), "Reimportar actualiza las 985 ofertas");
        assertEquals(1489, offerRepo.count(), "El total de ofertas no cambia tras reimportar");
    }
}
