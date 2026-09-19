package org.example.backendbvaberiaperfumes.service.nso;

/**
 * Operacion NSO que choca con el estado actual (el controlador la devuelve como 409 {message}):
 * activar el filtro sin lista cargada, verificacion masiva ya corriendo, codigo o SKU que ya existe,
 * candidato ya resuelto. El mensaje es para la admin, en espanol.
 */
public class NsoConflictException extends IllegalStateException {

    public NsoConflictException(String message) {
        super(message);
    }
}
