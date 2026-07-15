package org.example.backendbvaberiaperfumes.dto;

/**
 * Estado publico del consolidado para el aviso/countdown de la tienda.
 * Fechas en EPOCH MILLIS + serverNowMs: el navegador calcula el restante contra
 * la hora del SERVIDOR (elimina bugs de zona horaria y de reloj del visitante).
 * Campos publicos -> JSON directo (estilo AllocationResponse).
 */
public class ConsolidadoPublicDTO {
    public Long id;
    public String status;          // PROGRAMADO | ABIERTO | CERRADO | ENTREGADO | NONE
    public String title;
    public String description;
    public Long startAtMs;
    public Long endsAtMs;
    public Long serverNowMs;
    public boolean extended;       // el admin movio el plazo hacia adelante: "¡Plazo extendido!"
    public Long imageMediaId;
    /**
     * Fuente de verdad del gating: ABIERTO y (sin plazo o plazo no vencido).
     * Si el scheduler llega tarde (cold start), este flag ya sale false.
     */
    public boolean open;
}
