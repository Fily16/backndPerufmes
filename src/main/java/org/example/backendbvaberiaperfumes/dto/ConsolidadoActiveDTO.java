package org.example.backendbvaberiaperfumes.dto;

import org.example.backendbvaberiaperfumes.model.Consolidado;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * GET /api/consolidados/active (PUBLICO): solo lo que la tienda y el panel necesitan para saber CUAL es el
 * consolidado abierto (el checkout solo mira si responde 200; admin-shell y plan de compra usan el id).
 * Nunca lleva pedidos ni datos de clientes, ni costos/ganancias (totalCostUsd, projectedProfitPen, notas): eso
 * es del panel, por rutas con JWT (GET /api/consolidados, /{id}, /{id}/orders).
 * Mismos nombres y tipos que la entidad, para que el JSON de estos campos no cambie.
 */
public class ConsolidadoActiveDTO {
    public Long id;
    public String status;
    public String title;
    public String description;
    public Instant startAt;
    public Instant endsAt;
    public Boolean extended;
    public Long imageMediaId;
    public LocalDateTime openDate;
    public LocalDateTime closeDate;
    public LocalDateTime createdAt;

    public static ConsolidadoActiveDTO of(Consolidado c) {
        ConsolidadoActiveDTO d = new ConsolidadoActiveDTO();
        d.id = c.getId();
        d.status = c.getStatus();
        d.title = c.getTitle();
        d.description = c.getDescription();
        d.startAt = c.getStartAt();
        d.endsAt = c.getEndsAt();
        d.extended = c.getExtended();
        d.imageMediaId = c.getImageMediaId();
        d.openDate = c.getOpenDate();
        d.closeDate = c.getCloseDate();
        d.createdAt = c.getCreatedAt();
        return d;
    }
}
