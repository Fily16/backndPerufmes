package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.service.PricingService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final PricingService pricingService;

    public ConfigController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    // Public: return config needed by frontend
    @GetMapping("/public")
    public Map<String, Object> getPublicConfig() {
        return Map.of(
            "yapeNumber", pricingService.getYapeNumber(),
            "exchangeRate", pricingService.getExchangeRate(),
            "minOrderUsd", pricingService.getMinOrderUsd()
        );
    }
}
