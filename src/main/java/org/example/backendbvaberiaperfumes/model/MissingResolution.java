package org.example.backendbvaberiaperfumes.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Estado de resolución de un perfume faltante dentro de un consolidado (metadato de
 * seguimiento; NO participa en la asignación de compra). Permite separar:
 *  - CRIST_PENDING  : Caso A, se comprará en CristFragance (precio ya calculado) — por defecto.
 *  - CRIST_BOUGHT   : Caso A ya comprado en CristFragance (check del admin, persistente).
 *  - UNAVAILABLE    : Caso B, imposible de conseguir → se avisa al cliente.
 * Único por (consolidado, producto).
 */
@Entity
@Table(name = "missing_resolution",
        uniqueConstraints = @UniqueConstraint(columnNames = {"consolidado_id", "product_id"}))
public class MissingResolution {

    public static final String CRIST_PENDING = "CRIST_PENDING";
    public static final String CRIST_BOUGHT = "CRIST_BOUGHT";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consolidado_id", nullable = false)
    private Long consolidadoId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 32)
    private String status = CRIST_PENDING;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public MissingResolution() { }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConsolidadoId() { return consolidadoId; }
    public void setConsolidadoId(Long consolidadoId) { this.consolidadoId = consolidadoId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
