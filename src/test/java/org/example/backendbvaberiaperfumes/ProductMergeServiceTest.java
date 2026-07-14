package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.ProductMergeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La fusion re-apunta TODO (ofertas, pedidos, retail, promos), conserva la
 * curaduria y deja el duplicado archivado con mergedIntoId (sin hard delete).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mergetest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class ProductMergeServiceTest {

    @Autowired ProductMergeService mergeService;
    @Autowired ProductRepository productRepo;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired OrderItemRepository orderItemRepo;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired RetailInventoryRepository retailInvRepo;
    @Autowired RetailSaleRepository retailSaleRepo;
    @Autowired PromotionRepository promotionRepo;
    @Autowired PromotionItemRepository promoItemRepo;
    @Autowired MatchCandidateRepository candidateRepo;

    @Test
    void fusionaDuplicadoDentroDelCanonico() {
        // ===== Montaje: canonico importado (con GTIN) y duplicado del seed (curado, sin GTIN) =====
        Product canonical = new Product();
        canonical.setSku("TEST-ZI-99990360593661");
        canonical.setBrand("MarcaTest");
        canonical.setName("Khamrah Qahwa");
        canonical.setMl(100);
        canonical.setGtin("99990360593661");
        canonical.setWeightG(600); // peso por defecto de import
        canonical.setAvailable(true);
        canonical = productRepo.save(canonical);

        Product duplicate = new Product();
        duplicate.setSku("TEST-LAT-KHAMRAH-QAHWA-100");
        duplicate.setBrand("MarcaTest");
        duplicate.setName("Khamrah Qahwa");
        duplicate.setMl(100);
        duplicate.setImageUrl("imagenes/khamrah-qahwa.jpg");   // curaduria del seed
        duplicate.setNotesTop("cafe,cardamomo");
        duplicate.setNotesMiddle("praline");
        duplicate.setNotesBase("vainilla");
        duplicate.setWeightG(530);                              // peso ajustado a mano
        duplicate.setAvailable(true);
        duplicate = productRepo.save(duplicate);

        Supplier fs = supplierRepo.findByName("FragranceSenseTest")
                .orElseGet(() -> supplierRepo.save(new Supplier("FragranceSenseTest", 0.0, false)));

        SupplierOffer offer = new SupplierOffer();
        offer.setProduct(duplicate);
        offer.setSupplier(fs);
        offer.setOfferKey("NOUPC#lattafa-khamrah-qahwa-100");
        offer.setCostUsd(19.0);
        offer.setInStock(true);
        offer.setLastImportedAt(LocalDateTime.now());
        offerRepo.save(offer);

        // Pedido historico apuntando al duplicado, con precio snapshot.
        Consolidado c = new Consolidado();
        c.setStatus("ABIERTO");
        c = consolidadoRepo.save(c);
        Order order = new Order();
        order.setConsolidado(c);
        order.setClientName("Cliente Test");
        order.setClientPhone("999999999");
        order = orderRepo.save(order);
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(duplicate);
        item.setQuantity(2);
        item.setUnitPricePen(95.0);
        item.calculateSubtotal();
        orderItemRepo.save(item);

        // Lote retail y venta apuntando al duplicado.
        RetailInventory lot = new RetailInventory();
        lot.setProduct(duplicate);
        lot.setQuantity(3);
        lot.setCostPerUnitPen(80.0);
        retailInvRepo.save(lot);
        RetailSale sale = new RetailSale();
        sale.setProduct(duplicate);
        sale.setQuantity(1);
        sale.setSalePricePen(120.0);
        sale.setCostPen(80.0);
        retailSaleRepo.save(sale);

        // Promo que incluye al duplicado.
        Promotion promo = new Promotion();
        promo.setName("Pack Khamrah");
        promo.setPricePen(180.0);
        promo = promotionRepo.save(promo);
        PromotionItem pi = new PromotionItem();
        pi.setPromotion(promo);
        pi.setProductId(duplicate.getId());
        pi.setName("Khamrah Qahwa");
        promoItemRepo.save(pi);

        // Candidato pendiente que la fusion debe resolver.
        MatchCandidate mc = new MatchCandidate();
        mc.setKind("DEDUP_SCAN");
        mc.setSourceProductId(duplicate.getId());
        mc.setTargetProductId(canonical.getId());
        mc.setScore(1.0);
        candidateRepo.save(mc);

        // ===== Fusion =====
        ProductMergeService.MergeResult res = mergeService.merge(canonical.getId(), duplicate.getId());

        // ===== Asserts =====
        assertEquals(1, res.offersMoved);
        assertEquals(1, res.orderItemsMoved);
        assertEquals(1, res.retailLotsMoved);
        assertEquals(1, res.retailSalesMoved);
        assertEquals(1, res.promoItemsMoved);

        // Ofertas cuelgan del canonico.
        List<SupplierOffer> offers = offerRepo.findByProduct_Id(canonical.getId());
        assertEquals(1, offers.size());
        assertEquals("NOUPC#lattafa-khamrah-qahwa-100", offers.get(0).getOfferKey());

        // El snapshot del pedido queda intacto y re-apuntado.
        OrderItem movedItem = orderItemRepo.findByProductId(canonical.getId()).get(0);
        assertEquals(95.0, movedItem.getUnitPricePen());
        assertEquals(190.0, movedItem.getSubtotalPen());
        assertTrue(orderItemRepo.findByProductId(duplicate.getId()).isEmpty());

        // Retail y promo re-apuntados.
        assertEquals(1, retailInvRepo.findByProductId(canonical.getId()).size());
        assertEquals(1, retailSaleRepo.findByProductId(canonical.getId()).size());
        assertEquals(1, promoItemRepo.findByProductId(canonical.getId()).size());

        // Curaduria copiada al canonico (foto, notas, peso ajustado).
        Product mergedCanonical = productRepo.findById(canonical.getId()).orElseThrow();
        assertEquals("imagenes/khamrah-qahwa.jpg", mergedCanonical.getImageUrl());
        assertEquals("cafe,cardamomo", mergedCanonical.getNotesTop());
        assertEquals(530, mergedCanonical.getWeightG());
        assertEquals("99990360593661", mergedCanonical.getGtin());

        // Duplicado archivado con puntero al canonico (auditoria), nunca borrado.
        Product archived = productRepo.findById(duplicate.getId()).orElseThrow();
        assertTrue(archived.getArchived());
        assertFalse(archived.getAvailable());
        assertEquals(canonical.getId(), archived.getMergedIntoId());

        // Candidato resuelto como aceptado.
        MatchCandidate resolved = candidateRepo.findById(mc.getId()).orElseThrow();
        assertEquals("ACCEPTED", resolved.getStatus());

        // Fusionar dos veces debe fallar.
        final Long canonId = canonical.getId();
        final Long dupId = duplicate.getId();
        assertThrows(IllegalStateException.class, () -> mergeService.merge(canonId, dupId));
    }
}
