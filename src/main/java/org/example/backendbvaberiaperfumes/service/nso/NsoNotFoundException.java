package org.example.backendbvaberiaperfumes.service.nso;

import java.util.NoSuchElementException;

/**
 * Algo que la admin pidio no existe (el controlador lo devuelve como 404 {message}).
 * canCreate=true cuando es un codigo NSO con formato valido que no esta en la lista: el frontend ofrece
 * "agregarlo" (contrato: 404 {message, canCreate:true}).
 */
public class NsoNotFoundException extends NoSuchElementException {

    private final boolean canCreate;

    public NsoNotFoundException(String message) {
        this(message, false);
    }

    public NsoNotFoundException(String message, boolean canCreate) {
        super(message);
        this.canCreate = canCreate;
    }

    public boolean isCanCreate() {
        return canCreate;
    }
}
