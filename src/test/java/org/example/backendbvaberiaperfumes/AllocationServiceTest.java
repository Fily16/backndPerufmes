package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:alloctest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class AllocationServiceTest {

    @Autowired AllocationService allocationService;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;
    @Autowired ProductRepository productRepo;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired OrderRepository orderRepo;

    @Test
    void asignaPorCostoYPriorizaZimaxx() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();

        Product a = product("ALLOC-A", "Lattafa", "Khamrah");      // Magnet 10 / Zimaxx 12
        Product b = product("ALLOC-B", "Lattafa", "Yara");         // solo Zimaxx 30
        Product c = product("ALLOC-C", "Afnan", "9pm");            // solo Magnet 8

        offer(a, magnet, "A-MAG", 10.0);
        offer(a, zimaxx, "A-ZX", 12.0);
        offer(b, zimaxx, "B-ZX", 30.0);
        offer(c, magnet, "C-MAG", 8.0);

        Consolidado con = new Consolidado();
        con.setStatus("ABIERTO");
        con = consolidadoRepo.save(con);

        Order o = new Order();
        o.setConsolidado(con);
        o.setClientName("Cliente Test");
        o.setClientPhone("999");
        o.setPaymentStatus("SEPARADO"); // estado activo
        o.getItems().add(item(o, a, 1));
        o.getItems().add(item(o, b, 1));
        o.getItems().add(item(o, c, 1));
        orderRepo.save(o);

        AllocationResponse r = allocationService.computeAllocation(con.getId());

        // baseline = A@10 + B@30 + C@8
        assertEquals(48.0, r.baselineTotalUsd, 0.001);
        // priorizar Zimaxx (min 2000): se mueve A (penalidad 2), C no tiene oferta Zimaxx
        assertEquals(2.0, r.extraCostUsd, 0.001, "Forzar Zimaxx cuesta $2 extra (mover A)");
        assertFalse(r.zimaxxMinReached, "Con min 2000 no se alcanza");
        assertTrue(r.zimaxxGapUsd > 1900, "Falta casi todo para 2000");
        assertTrue(r.unfulfillable.isEmpty(), "Todo tiene stock");

        AllocationResponse.SupplierAllocation zg = group(r, "Zimaxx");
        AllocationResponse.SupplierAllocation mg = group(r, "Magnet");
        assertEquals(42.0, zg.subtotalUsd, 0.001, "Zimaxx = A(12) + B(30)");
        assertEquals(8.0, mg.subtotalUsd, 0.001, "Magnet = C(8)");
        assertTrue(zg.lines.stream().anyMatch(l -> l.name.equals("Khamrah") && l.movedToReachMin),
                "A debe aparecer movido a Zimaxx");

        // Con un minimo bajo (40) si se alcanza moviendo solo A
        zimaxx.setMinOrderUsd(40.0);
        supplierRepo.save(zimaxx);
        AllocationResponse r2 = allocationService.computeAllocation(con.getId());
        assertTrue(r2.zimaxxMinReached, "Con min 40 si se alcanza");
        assertEquals(0.0, r2.zimaxxGapUsd, 0.001);
    }

    private Product product(String sku, String brand, String name) {
        Product p = new Product();
        p.setSku(sku);
        p.setBrand(brand);
        p.setName(name);
        p.setMl(100);
        p.setAvailable(true);
        p.setArchived(false);
        return productRepo.save(p);
    }

    private void offer(Product p, Supplier s, String key, double cost) {
        SupplierOffer o = new SupplierOffer();
        o.setProduct(p);
        o.setSupplier(s);
        o.setOfferKey(key);
        o.setCostUsd(cost);
        o.setInStock(true);
        offerRepo.save(o);
    }

    private OrderItem item(Order o, Product p, int qty) {
        OrderItem it = new OrderItem();
        it.setOrder(o);
        it.setProduct(p);
        it.setQuantity(qty);
        it.setUnitPricePen(1.0);
        it.calculateSubtotal();
        return it;
    }

    private AllocationResponse.SupplierAllocation group(AllocationResponse r, String name) {
        return r.suppliers.stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }
}
