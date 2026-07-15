package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.Consolidado;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Ciclo de vida automatico del consolidado (patron KeepAliveService):
 *  - PROGRAMADO cuya fecha de apertura llego  -> ABIERTO.
 *  - ABIERTO cuyo plazo (endsAt) vencio       -> CERRADO (reusa closeConsolidado:
 *    status + closeDate + recalculo; todos los candados de pedidos reaccionan solos).
 *
 * Un ABIERTO con endsAt == null (el consolidado historico de produccion) JAMAS se toca.
 * initialDelay corto: si Render despierta de un cold start con el plazo ya vencido,
 * lo sella de inmediato (ademas /current calcula "open" contra la hora real, asi que
 * los pedidos ya estaban bloqueados aunque el status siguiera ABIERTO).
 */
@Service
public class ConsolidadoScheduler {

    private final ConsolidadoRepository consolidadoRepo;
    private final ConsolidadoService consolidadoService;

    public ConsolidadoScheduler(ConsolidadoRepository consolidadoRepo,
                                ConsolidadoService consolidadoService) {
        this.consolidadoRepo = consolidadoRepo;
        this.consolidadoService = consolidadoService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void tick() {
        Instant now = Instant.now();

        for (Consolidado c : consolidadoRepo.findByStatus("PROGRAMADO")) {
            if (c.getStartAt() != null && !c.getStartAt().isAfter(now)) {
                try {
                    consolidadoService.activateScheduled(c.getId());
                    System.out.println("[CONSOLIDADO] #" + c.getId() + " abierto automaticamente ("
                            + (c.getTitle() != null ? c.getTitle() : "sin titulo") + ")");
                } catch (Exception e) {
                    System.err.println("[CONSOLIDADO] No se pudo abrir #" + c.getId() + ": " + e.getMessage());
                }
            }
        }

        for (Consolidado c : consolidadoRepo.findByStatus("ABIERTO")) {
            if (c.getEndsAt() != null && c.getEndsAt().isBefore(now)) {
                try {
                    consolidadoService.closeConsolidado(c.getId());
                    System.out.println("[CONSOLIDADO] #" + c.getId() + " cerrado automaticamente (plazo vencido)");
                } catch (Exception e) {
                    System.err.println("[CONSOLIDADO] No se pudo cerrar #" + c.getId() + ": " + e.getMessage());
                }
            }
        }
    }
}
