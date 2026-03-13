package org.example.backendbvaberiaperfumes.service;

import org.example.backendbvaberiaperfumes.model.*;
import org.example.backendbvaberiaperfumes.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetailService {

    private final RetailInventoryRepository inventoryRepo;
    private final RetailSaleRepository saleRepo;
    private final ProductRepository productRepo;

    public RetailService(RetailInventoryRepository inventoryRepo, RetailSaleRepository saleRepo,
                         ProductRepository productRepo) {
        this.inventoryRepo = inventoryRepo;
        this.saleRepo = saleRepo;
        this.productRepo = productRepo;
    }

    // --- Inventory ---
    public List<RetailInventory> getAllInventory() {
        return inventoryRepo.findAll();
    }

    public List<RetailInventory> getInStockInventory() {
        return inventoryRepo.findByQuantityGreaterThan(0);
    }

    @Transactional
    public RetailInventory addStock(Long productId, int quantity, Double costPerUnitPen, String notes) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        RetailInventory inv = new RetailInventory();
        inv.setProduct(product);
        inv.setQuantity(quantity);
        inv.setCostPerUnitPen(costPerUnitPen);
        inv.setNotes(notes);
        return inventoryRepo.save(inv);
    }

    // --- Sales ---
    public List<RetailSale> getAllSales() {
        return saleRepo.findAll();
    }

    @Transactional
    public RetailSale registerSale(Long productId, int quantity, double salePricePen, String channel) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        // Find inventory with stock and reduce quantity
        List<RetailInventory> inventories = inventoryRepo.findByProductId(productId);
        int remaining = quantity;
        double totalCost = 0;

        for (RetailInventory inv : inventories) {
            if (remaining <= 0) break;
            if (inv.getQuantity() > 0) {
                int take = Math.min(inv.getQuantity(), remaining);
                inv.setQuantity(inv.getQuantity() - take);
                totalCost += (inv.getCostPerUnitPen() != null ? inv.getCostPerUnitPen() : 0) * take;
                remaining -= take;
                inventoryRepo.save(inv);
            }
        }

        if (remaining > 0) {
            throw new RuntimeException("Not enough stock. Missing " + remaining + " units.");
        }

        RetailSale sale = new RetailSale();
        sale.setProduct(product);
        sale.setQuantity(quantity);
        sale.setSalePricePen(salePricePen);
        sale.setCostPen(totalCost);
        sale.setProfitPen((salePricePen * quantity) - totalCost);
        sale.setChannel(channel != null ? channel : "WHATSAPP");
        return saleRepo.save(sale);
    }

    /**
     * Adjusts inventory so that the total stock for a product matches the target.
     * Used when syncing from Google Sheet (Sheet is source of truth for form sales).
     */
    @Transactional
    public int adjustStockToMatch(Long productId, int targetStock) {
        List<RetailInventory> inventories = inventoryRepo.findByProductId(productId);
        int currentStock = inventories.stream().mapToInt(RetailInventory::getQuantity).sum();

        if (targetStock >= currentStock) return 0; // Sheet has same or more, no adjustment

        int toRemove = currentStock - targetStock;
        int removed = 0;
        for (RetailInventory inv : inventories) {
            if (toRemove <= 0) break;
            if (inv.getQuantity() > 0) {
                int take = Math.min(inv.getQuantity(), toRemove);
                inv.setQuantity(inv.getQuantity() - take);
                toRemove -= take;
                removed += take;
                inventoryRepo.save(inv);
            }
        }
        return removed;
    }

    public int getTotalStock() {
        Integer total = inventoryRepo.sumTotalStock();
        return total != null ? total : 0;
    }

    // ESTE ES EL MÉTODO QUE SE HABÍA BORRADO
    public Map<Long, Integer> getStockByProduct() {
        Map<Long, Integer> map = new HashMap<>();
        for (Object[] row : inventoryRepo.findStockByProduct()) {
            map.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return map;
    }

    public double getTotalRevenue() {
        Double total = saleRepo.sumTotalRevenue();
        return total != null ? total : 0.0;
    }

    public double getTotalProfit() {
        Double total = saleRepo.sumTotalProfit();
        return total != null ? total : 0.0;
    }

}
