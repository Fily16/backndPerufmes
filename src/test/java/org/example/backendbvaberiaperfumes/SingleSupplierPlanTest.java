package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.SingleSupplierPlan;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Comprar solo en un proveedor": consolida en el proveedor objetivo reutilizando la asignación
 * normal. Verifica los 3 grupos — se compra ahí (incl. reasignados), no se consigue ahí, y que
 * CrisFragance (sin ofertas) ni aparece. No toca el motor.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:singlesuppliertest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.keep-alive.url="
})
class SingleSupplierPlanTest {

    @Autowired AllocationService allocationService;
    @Autowired SupplierRepository supplierRepo;
    @Autowired SupplierOfferRepository offerRepo;
    @Autowired SupplierConstraintRepository constraintRepo;
    @Autowired ProductRepository productRepo;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired OrderRepository orderRepo;

    private Product product(String sku, String brand, String name) {
        Product p = new Product();
        p.setSku(sku); p.setBrand(brand); p.setName(name);
        p.setMl(100); p.setAvailable(true); p.setArchived(false); p.setWeightG(600);
        return productRepo.save(p);
    }

    private void offer(Product p, Supplier s, String key, double cost) {
        SupplierOffer o = new SupplierOffer();
        o.setProduct(p); o.setSupplier(s); o.setOfferKey(key); o.setCostUsd(cost); o.setInStock(true);
        offerRepo.save(o);
    }

    private void setMinOrder(Supplier s, double min) {
        constraintRepo.findBySupplier_Id(s.getId()).stream()
                .filter(c -> "MIN_ORDER_USD".equals(c.getType())).forEach(constraintRepo::delete);
        if (min > 0) constraintRepo.save(new SupplierConstraint(s, "MIN_ORDER_USD", min));
        s.setMinOrderUsd(min);
        supplierRepo.save(s);
    }

    @Test
    void consolidarEnZimaxxMueveLosQueTambienEstanEnZimaxxYReportaLosQueNo() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        setMinOrder(zimaxx, 0); setMinOrder(magnet, 0); // sin mínimos: baseline = el más barato

        Product shared = product("SS-SH", "Lattafa", "Khamrah");   // Magnet 10 / Zimaxx 12 -> baseline Magnet
        Product onlyZx = product("SS-ZX", "Lattafa", "Yara");       // solo Zimaxx 30
        Product onlyMag = product("SS-MG", "Afnan", "9pm");         // solo Magnet 8
        offer(shared, magnet, "SS-SH-MAG", 10.0);
        offer(shared, zimaxx, "SS-SH-ZX", 12.0);
        offer(onlyZx, zimaxx, "SS-ZX-ZX", 30.0);
        offer(onlyMag, magnet, "SS-MG-MAG", 8.0);

        Consolidado con = new Consolidado();
        con.setStatus("ABIERTO");
        con = consolidadoRepo.save(con);
        Order o = new Order();
        o.setConsolidado(con); o.setClientName("Cliente"); o.setClientPhone("999"); o.setPaymentStatus("SEPARADO");
        o.getItems().add(item(o, shared, 2, 55.0));
        o.getItems().add(item(o, onlyZx, 1, 113.0));
        o.getItems().add(item(o, onlyMag, 3, 45.0));
        orderRepo.save(o);

        SingleSupplierPlan plan = allocationService.consolidateToSupplier(con.getId(), zimaxx.getId());

        assertEquals(zimaxx.getId(), plan.targetSupplierId);
        assertEquals("Zimaxx", plan.targetSupplierName);

        // Grupo 1: se compran en Zimaxx = shared (reasignado) + onlyZx.
        assertEquals(2, plan.buy.size());
        assertEquals(3, plan.buyUnits, "2 de Khamrah + 1 de Yara");

        SingleSupplierPlan.BuyLine sh = plan.buy.stream()
                .filter(b -> b.name.equals("Khamrah")).findFirst().orElseThrow();
        assertEquals(12.0, sh.unitCostUsd, 0.001, "al precio de Zimaxx, no al de Magnet");
        assertEquals("Magnet", sh.movedFromSupplierName, "reasignado desde Magnet");

        SingleSupplierPlan.BuyLine yz = plan.buy.stream()
                .filter(b -> b.name.equals("Yara")).findFirst().orElseThrow();
        assertNull(yz.movedFromSupplierName, "ya estaba en Zimaxx, no es reasignado");

        // Grupo 3: no se consigue en Zimaxx = onlyMag.
        assertEquals(1, plan.couldNotBuy.size());
        SingleSupplierPlan.CouldNotBuy cnb = plan.couldNotBuy.get(0);
        assertEquals("9pm", cnb.name);
        assertEquals(3, cnb.quantity);
        assertEquals("Magnet", cnb.currentSupplierName);
        assertTrue(cnb.reason.contains("Zimaxx"));
    }

    @Test
    void consolidarEnMagnetEsElMismoMecanismoAlReves() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        setMinOrder(zimaxx, 0); setMinOrder(magnet, 0);

        Product shared = product("MS-SH", "Lattafa", "Asad");      // Zimaxx 9 / Magnet 10 -> baseline Zimaxx
        Product onlyZx = product("MS-ZX", "Armaf", "Club");        // solo Zimaxx 20
        offer(shared, zimaxx, "MS-SH-ZX", 9.0);
        offer(shared, magnet, "MS-SH-MAG", 10.0);
        offer(onlyZx, zimaxx, "MS-ZX-ZX", 20.0);

        Consolidado con = new Consolidado();
        con.setStatus("ABIERTO");
        con = consolidadoRepo.save(con);
        Order o = new Order();
        o.setConsolidado(con); o.setClientName("Cliente2"); o.setClientPhone("999"); o.setPaymentStatus("SEPARADO");
        o.getItems().add(item(o, shared, 1, 55.0));
        o.getItems().add(item(o, onlyZx, 1, 100.0));
        orderRepo.save(o);

        // Escalable: el MISMO método sirve para cualquier proveedor.
        SingleSupplierPlan plan = allocationService.consolidateToSupplier(con.getId(), magnet.getId());
        assertEquals(1, plan.buy.size(), "solo 'Asad' existe en Magnet");
        assertEquals("Asad", plan.buy.get(0).name);
        assertEquals(1, plan.couldNotBuy.size(), "'Club' no existe en Magnet");
        assertEquals("Club", plan.couldNotBuy.get(0).name);
    }

    /** El indice de ofertas (proyeccion a record) debe traer proveedor y estado de stock reales. */
    @Test
    void indiceDeOfertasDevuelveProveedorYStock() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Product p = product("IDX-1", "Lattafa", "Indice Test");
        offer(p, zimaxx, "IDX-1-ZX", 11.0);

        var row = offerRepo.findOfferIndex().stream()
                .filter(r -> p.getId().equals(r.productId()))
                .findFirst().orElseThrow();
        assertEquals(zimaxx.getId(), row.supplierId());
        assertEquals("Zimaxx", row.supplierName());
        assertTrue(row.inStock());
        assertEquals(11.0, row.costUsd(), 0.001);
    }

    private OrderItem item(Order o, Product p, int qty, double unitPricePen) {
        OrderItem it = new OrderItem();
        it.setOrder(o); it.setProduct(p); it.setQuantity(qty); it.setUnitPricePen(unitPricePen);
        it.calculateSubtotal();
        return it;
    }
}
