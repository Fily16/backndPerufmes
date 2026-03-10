package org.example.backendbvaberiaperfumes.dto;

public class FullBreakdownResponse {
    private BreakdownSection consolidado;
    private BreakdownSection miCompra;
    private BreakdownSection total;

    public BreakdownSection getConsolidado() { return consolidado; }
    public void setConsolidado(BreakdownSection consolidado) { this.consolidado = consolidado; }
    public BreakdownSection getMiCompra() { return miCompra; }
    public void setMiCompra(BreakdownSection miCompra) { this.miCompra = miCompra; }
    public BreakdownSection getTotal() { return total; }
    public void setTotal(BreakdownSection total) { this.total = total; }
}
