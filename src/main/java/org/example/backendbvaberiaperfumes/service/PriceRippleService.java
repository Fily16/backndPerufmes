package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.repository.ProductRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Recalculo de precios en SEGUNDO PLANO tras cambios de proveedor.
 *
 * Antes esto corria dentro de la misma peticion HTTP: con cientos de productos
 * contra una BD remota (Aiven) tardaba minutos y la peticion moria por timeout
 * ("No se pudo eliminar" / el activar-desactivar parecia no responder).
 * Ahora la peticion responde al instante y los precios se actualizan detras.
 */
@Service
public class PriceRippleService {

    private final ProductRepository productRepo;
    private final ExcelImportService importService;

    public PriceRippleService(ProductRepository productRepo, ExcelImportService importService) {
        this.productRepo = productRepo;
        this.importService = importService;
    }

    @Async
    @Transactional
    public void recomputeProducts(List<Long> productIds) {
        int done = 0;
        for (Long pid : productIds) {
            var p = productRepo.findById(pid).orElse(null);
            if (p != null) {
                try {
                    importService.recomputeProductPrice(p);
                    done++;
                } catch (Exception e) {
                    System.err.println("[RIPPLE] Producto " + pid + " no se pudo repreciar: " + e.getMessage());
                }
            }
        }
        System.out.println("[RIPPLE] Precios recalculados en segundo plano: " + done + "/" + productIds.size());
    }
}
