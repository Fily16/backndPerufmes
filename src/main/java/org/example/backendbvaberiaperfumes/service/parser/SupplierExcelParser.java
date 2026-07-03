package org.example.backendbvaberiaperfumes.service.parser;

import org.example.backendbvaberiaperfumes.dto.ParsedRow;

import java.io.InputStream;
import java.util.List;

/** Un parser por proveedor (los layouts de Excel son distintos). */
public interface SupplierExcelParser {
    /** Debe coincidir con Supplier.name (ej. "Zimaxx", "Magnet"). */
    String supplierName();
    List<ParsedRow> parse(InputStream is) throws Exception;
}
