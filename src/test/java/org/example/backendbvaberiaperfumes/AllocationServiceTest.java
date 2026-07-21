package org.example.backendbvaberiaperfumes;

import org.example.backendbvaberiaperfumes.dto.AllocationResponse;
import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.example.backendbvaberiaperfumes.service.AllocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optimizador v2: restricciones como datos (SupplierConstraint) y decision
 * FORZAR vs SALTAR por costo total (incluyendo ingreso perdido y relleno).
 */
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
    @Autowired SupplierConstraintRepository constraintRepo;
    @Autowired ProductRepository productRepo;
    @Autowired ConsolidadoRepository consolidadoRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired PurchasePlanRepository planRepo;
    @Autowired MissingResolutionRepository missingResolutionRepo;

    // ================= helpers =================

    private Product product(String sku, String brand, String name) {
        Product p = new Product();
        p.setSku(sku);
        p.setBrand(brand);
        p.setName(name);
        p.setMl(100);
        p.setAvailable(true);
        p.setArchived(false);
        p.setWeightG(600);
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

    private OrderItem item(Order o, Product p, int qty, double unitPricePen) {
        OrderItem it = new OrderItem();
        it.setOrder(o);
        it.setProduct(p);
        it.setQuantity(qty);
        it.setUnitPricePen(unitPricePen);
        it.calculateSubtotal();
        return it;
    }

    private Consolidado consolidadoWithOrder(java.util.function.BiConsumer<Order, Consolidado> filler) {
        Consolidado con = new Consolidado();
        con.setStatus("ABIERTO");
        con = consolidadoRepo.save(con);
        Order o = new Order();
        o.setConsolidado(con);
        o.setClientName("Cliente Test");
        o.setClientPhone("999");
        o.setPaymentStatus("SEPARADO");
        filler.accept(o, con);
        orderRepo.save(o);
        return con;
    }

    private void setMinOrder(Supplier s, double min) {
        constraintRepo.findBySupplier_Id(s.getId()).stream()
                .filter(c -> "MIN_ORDER_USD".equals(c.getType()))
                .forEach(constraintRepo::delete);
        if (min > 0) constraintRepo.save(new SupplierConstraint(s, "MIN_ORDER_USD", min));
        s.setMinOrderUsd(min);
        supplierRepo.save(s);
    }

    private AllocationResponse.SupplierAllocation group(AllocationResponse r, String name) {
        return r.suppliers.stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    // ================= tests =================

    @Test
    void forzarGanaCuandoElMinimoEsAlcanzable() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        setMinOrder(zimaxx, 40.0); // alcanzable moviendo una linea

        Product a = product("F1-A", "Lattafa", "Khamrah");   // Magnet 10 / Zimaxx 12
        Product b = product("F1-B", "Lattafa", "Yara");      // solo Zimaxx 30
        Product c = product("F1-C", "Afnan", "9pm");         // solo Magnet 8
        offer(a, magnet, "F1-A-MAG", 10.0);
        offer(a, zimaxx, "F1-A-ZX", 12.0);
        offer(b, zimaxx, "F1-B-ZX", 30.0);
        offer(c, magnet, "F1-C-MAG", 8.0);

        // Precios realistas: saltar Zimaxx perderia la venta de B (S/113 cobrados).
        Consolidado con = consolidadoWithOrder((o, cc) -> {
            o.getItems().add(item(o, a, 1, 55.0));
            o.getItems().add(item(o, b, 1, 113.0));
            o.getItems().add(item(o, c, 1, 45.0));
        });

        AllocationResponse r = allocationService.computeAllocation(con.getId());

        assertEquals(48.0, r.baselineTotalUsd, 0.001, "baseline = A@10 + B@30 + C@8");
        // Forzar: mover A a Zimaxx (penalidad $2) -> cart 42 >= 40. Saltar: pierde B (~$33+30/3.4).
        AllocationResponse.SupplierDecision d = r.skipAnalysis.stream()
                .filter(x -> x.name.equals("Zimaxx")).findFirst().orElseThrow();
        assertEquals("FORZAR", d.decision, "forzar ($50) < saltar ($18 + ingreso perdido): " + r.notes);
        assertTrue(r.lostSales.isEmpty());
        assertEquals(2.0, r.extraCostUsd, 0.001, "mover A cuesta $2");
        assertTrue(r.zimaxxMinReached);

        AllocationResponse.SupplierAllocation zg = group(r, "Zimaxx");
        assertEquals(42.0, zg.subtotalUsd, 0.001, "Zimaxx = A(12) + B(30)");
        assertTrue(zg.lines.stream().anyMatch(l -> l.name.equals("Khamrah") && l.movedToReachMin));
    }

    @Test
    void saltarGanaCuandoElMinimoEsInalcanzablementeCaro() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        setMinOrder(zimaxx, 5000.0); // absurdo para esta demanda

        Product a = product("F2-A", "Lattafa", "Asad");       // Zimaxx 9 / Magnet 10 (ZX gana la linea)
        Product b = product("F2-B", "Armaf", "Club de Nuit"); // Magnet 25 / Zimaxx 28
        offer(a, zimaxx, "F2-A-ZX", 9.0);
        offer(a, magnet, "F2-A-MAG", 10.0);
        offer(b, magnet, "F2-B-MAG", 25.0);
        offer(b, zimaxx, "F2-B-ZX", 28.0);

        Consolidado con = consolidadoWithOrder((o, cc) -> {
            o.getItems().add(item(o, a, 1, 55.0));
            o.getItems().add(item(o, b, 1, 110.0));
        });

        AllocationResponse r = allocationService.computeAllocation(con.getId());

        AllocationResponse.SupplierDecision d = r.skipAnalysis.stream()
                .filter(x -> x.name.equals("Zimaxx")).findFirst().orElseThrow();
        assertEquals("SALTAR", d.decision,
                "rellenar ~$4963 al 15% (~$744) es carisimo; A migra a Magnet por solo +$1");
        assertTrue(r.lostSales.isEmpty(), "nada se pierde: todo migra a Magnet");
        assertEquals(35.0, r.chosenTotalUsd, 0.001, "todo en Magnet: 10+25");
        assertFalse(r.zimaxxMinReached);
        assertTrue(r.suppliers.stream().noneMatch(s -> s.name.equals("Zimaxx")),
                "Zimaxx no recibe compra este ciclo");
    }

    @Test
    void minimoDeUnidadesDeFragranceSense() {
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        setMinOrder(zimaxx, 0);
        Supplier fs = supplierRepo.save(new Supplier("FragranceSense", 0.0, false));
        constraintRepo.save(new SupplierConstraint(fs, "MIN_UNITS", 3.0));

        // FS es mas barato en los tres productos, pero la demanda son 3 unidades justas.
        Product a = product("F3-A", "Lattafa", "Eclaire");
        Product b = product("F3-B", "Lattafa", "Ansaam Gold");
        Product c = product("F3-C", "Rasasi", "Hawas");
        offer(a, fs, "F3-A-FS", 18.0);
        offer(a, magnet, "F3-A-MAG", 21.0);
        offer(b, fs, "F3-B-FS", 15.0);
        offer(b, magnet, "F3-B-MAG", 16.0);
        offer(c, fs, "F3-C-FS", 24.0);
        offer(c, magnet, "F3-C-MAG", 27.0);

        Consolidado con = consolidadoWithOrder((o, cc) -> {
            o.getItems().add(item(o, a, 1, 90.0));
            o.getItems().add(item(o, b, 1, 80.0));
            o.getItems().add(item(o, c, 1, 115.0));
        });

        AllocationResponse r = allocationService.computeAllocation(con.getId());

        // Baseline ya pone las 3 unidades en FS -> su MIN_UNITS=3 queda satisfecho sin mover nada.
        assertEquals(57.0, r.baselineTotalUsd, 0.001);
        assertEquals(57.0, r.chosenTotalUsd, 0.001, "no hay sobrecosto: el minimo se cumple solo");
        assertTrue(r.lostSales.isEmpty());
        AllocationResponse.SupplierAllocation fg = group(r, "FragranceSense");
        assertEquals(3, fg.lines.stream().mapToInt(l -> l.quantity).sum());
    }

    @Test
    void minimoDeUnidadesInsatisfechoDecideForzarOSaltar() {
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        setMinOrder(zimaxx, 0);
        Supplier fs = supplierRepo.save(new Supplier("FragranceSense2", 0.0, false));
        constraintRepo.save(new SupplierConstraint(fs, "MIN_UNITS", 4.0));

        // FS gana solo en A ($5 menos); las otras 2 lineas son mas baratas en Magnet por poco.
        Product a = product("F4-A", "Lattafa", "Mayar");
        Product b = product("F4-B", "Lattafa", "Sutan");
        Product c = product("F4-C", "Armaf", "Ventana");
        offer(a, fs, "F4-A-FS", 15.0);
        offer(a, magnet, "F4-A-MAG", 20.0);
        offer(b, fs, "F4-B-FS", 16.5);
        offer(b, magnet, "F4-B-MAG", 16.0);
        offer(c, fs, "F4-C-FS", 22.5);
        offer(c, magnet, "F4-C-MAG", 22.0);

        Consolidado con = consolidadoWithOrder((o, cc) -> {
            o.getItems().add(item(o, a, 2, 75.0));
            o.getItems().add(item(o, b, 1, 78.0));
            o.getItems().add(item(o, c, 1, 100.0));
        });

        AllocationResponse r = allocationService.computeAllocation(con.getId());
        // Baseline: A(x2)@FS=30, B@MAG=16, C@MAG=22 -> FS tiene 2 de 4 unidades.
        // FORZAR: mover B (+0.5) y C (+0.5) a FS -> $69 total (+1). SALTAR: A se va a Magnet (+10) -> $78.
        AllocationResponse.SupplierDecision d = r.skipAnalysis.stream()
                .filter(x -> x.name.startsWith("FragranceSense2")).findFirst().orElseThrow();
        assertEquals("FORZAR", d.decision, String.valueOf(r.notes));
        AllocationResponse.SupplierAllocation fg = group(r, "FragranceSense2");
        assertEquals(4, fg.lines.stream().mapToInt(l -> l.quantity).sum(), "las 4 unidades quedan en FS");
        assertEquals(69.0, r.chosenTotalUsd, 0.001);
    }

    @Test
    void guardiaDeMargenBloqueaConfirmSinForce() {
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        setMinOrder(zimaxx, 0);

        Product a = product("F5-A", "Lattafa", "Oud Mood");
        offer(a, magnet, "F5-A-MAG", 30.0); // landed ~ (30+5.4+0.875)*3.4 = ~123 PEN

        // Vendido a S/95: margen negativo -> bajo el piso (S/8).
        Consolidado con = consolidadoWithOrder((o, cc) ->
                o.getItems().add(item(o, a, 1, 95.0)));

        AllocationResponse r = allocationService.computeAndSaveDraft(con.getId());
        assertNotNull(r.planId);
        assertFalse(r.marginWarnings.isEmpty(), "la venta quedaria bajo el piso de margen");

        // confirm sin force -> excepcion con el detalle (el controller la mapea a 409)
        Long planId = r.planId;
        AllocationService.MarginFloorException ex = assertThrows(
                AllocationService.MarginFloorException.class,
                () -> allocationService.confirmPlan(con.getId(), planId, false));
        assertFalse(ex.warnings.isEmpty());

        // con force=true se confirma
        PurchasePlan plan = allocationService.confirmPlan(con.getId(), planId, true);
        assertEquals("CONFIRMED", plan.getStatus());
        assertNotNull(plan.getConfirmedAt());
    }

    @Test
    void trazabilidadDeLaDecisionSeExponeSinCambiarLaAsignacion() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        setMinOrder(magnet, 0.0);   // neutraliza mínimos que otros tests dejaron en la H2 compartida
        setMinOrder(zimaxx, 40.0);

        Product a = product("TR-A", "Lattafa", "Khamrah");   // Magnet 10 / Zimaxx 12 (movido a ZX)
        Product b = product("TR-B", "Lattafa", "Yara");      // solo Zimaxx 30 (único)
        Product c = product("TR-C", "Afnan", "9pm");         // solo Magnet 8 (más barato, único)
        offer(a, magnet, "TR-A-MAG", 10.0);
        offer(a, zimaxx, "TR-A-ZX", 12.0);
        offer(b, zimaxx, "TR-B-ZX", 30.0);
        offer(c, magnet, "TR-C-MAG", 8.0);

        Consolidado con = consolidadoWithOrder((o, cc) -> {
            o.getItems().add(item(o, a, 1, 55.0));
            o.getItems().add(item(o, b, 1, 113.0));
            o.getItems().add(item(o, c, 1, 45.0));
        });

        AllocationResponse r = allocationService.computeAllocation(con.getId());

        // La asignacion no cambia: mismo escenario que forzarGana... -> Zimaxx = A(12)+B(30) = 42.
        assertEquals(2.0, r.extraCostUsd, 0.001);
        AllocationResponse.SupplierAllocation zg = group(r, "Zimaxx");

        // Khamrah: movido a Zimaxx, 2 alternativas (una elegida, una mas barata), motivo "Movido".
        AllocationResponse.AllocationLine kh = zg.lines.stream()
                .filter(l -> l.name.equals("Khamrah")).findFirst().orElseThrow();
        assertEquals(2, kh.alternatives.size(), "Khamrah tiene precio en 2 proveedores");
        assertEquals(zimaxx.getId(), kh.chosenSupplierId);
        assertEquals(magnet.getId(), kh.cheapestSupplierId);
        assertTrue(kh.alternatives.stream().anyMatch(ap -> ap.chosen && ap.supplierId.equals(zimaxx.getId())));
        assertTrue(kh.alternatives.stream().anyMatch(ap -> ap.cheapest && ap.supplierId.equals(magnet.getId())));
        assertTrue(kh.reason.startsWith("Movido"), kh.reason);
        // alternativas ordenadas por costo ascendente (el mas barato primero)
        assertTrue(kh.alternatives.get(0).unitCostUsd <= kh.alternatives.get(1).unitCostUsd);

        // Yara: un solo proveedor -> motivo "Único proveedor con stock".
        AllocationResponse.AllocationLine ya = zg.lines.stream()
                .filter(l -> l.name.equals("Yara")).findFirst().orElseThrow();
        assertEquals(1, ya.alternatives.size());
        assertEquals("Único proveedor con stock", ya.reason);
    }

    @Test
    void resolucionDeFaltanteHaceUpsertPorConsolidadoYProducto() {
        Long consolidadoId = 777L, productId = 999L;
        MissingResolution m = new MissingResolution();
        m.setConsolidadoId(consolidadoId);
        m.setProductId(productId);
        m.setStatus(MissingResolution.CRIST_BOUGHT);
        missingResolutionRepo.save(m);

        MissingResolution found = missingResolutionRepo
                .findByConsolidadoIdAndProductId(consolidadoId, productId).orElseThrow();
        assertEquals(MissingResolution.CRIST_BOUGHT, found.getStatus());

        // Actualizar (upsert): mismo par -> misma fila, nuevo estado.
        found.setStatus(MissingResolution.UNAVAILABLE);
        missingResolutionRepo.save(found);
        assertEquals(1, missingResolutionRepo.findByConsolidadoId(consolidadoId).size());
        assertEquals(MissingResolution.UNAVAILABLE,
                missingResolutionRepo.findByConsolidadoIdAndProductId(consolidadoId, productId).orElseThrow().getStatus());
    }

    @Test
    void dosMinimosSimultaneosSeEnumeranExactamente() {
        Supplier zimaxx = supplierRepo.findByName("Zimaxx").orElseThrow();
        Supplier magnet = supplierRepo.findByName("Magnet").orElseThrow();
        setMinOrder(zimaxx, 60.0);
        setMinOrder(magnet, 50.0);

        // Tres productos disponibles en ambos; ninguno de los dos carritos llega solo.
        Product a = product("F6-A", "Lattafa", "Ajwad");
        Product b = product("F6-B", "Lattafa", "Ramz");
        Product c = product("F6-C", "Afnan", "Supremacy");
        offer(a, zimaxx, "F6-A-ZX", 20.0);
        offer(a, magnet, "F6-A-MAG", 22.0);
        offer(b, zimaxx, "F6-B-ZX", 25.0);
        offer(b, magnet, "F6-B-MAG", 24.0);
        offer(c, zimaxx, "F6-C-ZX", 30.0);
        offer(c, magnet, "F6-C-MAG", 29.0);

        Consolidado con = consolidadoWithOrder((o, cc) -> {
            o.getItems().add(item(o, a, 1, 95.0));
            o.getItems().add(item(o, b, 1, 105.0));
            o.getItems().add(item(o, c, 1, 125.0));
        });

        AllocationResponse r = allocationService.computeAllocation(con.getId());
        // baseline: A@ZX 20, B@MAG 24, C@MAG 29 -> ZX=20<60, MAG=53>=50: solo ZX insatisfecho.
        // FORZAR ZX: mover B(+1) y C(+1)? Con B basta? 20+25=45<60; +C: 75>=60 (+2 total, pero
        // rompe el minimo de Magnet: 0 lineas -> Magnet sin restriccion violada? cart vacio:
        // deficit 50>0 PERO un carrito vacio no es un pedido -> el optimizador lo trata como
        // proveedor saltado sin costo. Alternativa: SALTAR ZX: A migra a MAG (+2) -> MAG=75.
        // Ambos caminos cuestan +2; cualquiera es valido; lo importante: 0 ventas perdidas
        // y todos los carritos NO vacios cumplen su minimo.
        assertTrue(r.lostSales.isEmpty(), String.valueOf(r.notes));
        for (AllocationResponse.SupplierAllocation g : r.suppliers) {
            if (g.subtotalUsd > 0 && g.minOrderUsd > 0) {
                assertTrue(g.subtotalUsd >= g.minOrderUsd,
                        g.name + " compra " + g.subtotalUsd + " < min " + g.minOrderUsd);
            }
        }
        assertEquals(75.0, r.chosenTotalUsd, 0.001, "+2 sobre baseline 73");
    }
}
