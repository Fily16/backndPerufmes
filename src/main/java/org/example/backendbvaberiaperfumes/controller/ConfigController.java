package org.example.backendbvaberiaperfumes.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.service.PricingService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final PricingService pricingService;
    private final AppConfigRepository configRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigController(PricingService pricingService, AppConfigRepository configRepo) {
        this.pricingService = pricingService;
        this.configRepo = configRepo;
    }

    // Public: return config needed by frontend
    @GetMapping("/public")
    public Map<String, Object> getPublicConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("yapeNumber", pricingService.getYapeNumber());
        cfg.put("exchangeRate", pricingService.getExchangeRate());
        cfg.put("minOrderUsd", pricingService.getMinOrderUsd());
        cfg.put("banners", readJsonArray("home_banners"));
        cfg.put("promos", readJsonArray("home_promos"));
        return cfg;
    }

    /** Lee un arreglo JSON guardado en app_config. Si falta o esta mal, devuelve lista vacia. */
    private Object readJsonArray(String key) {
        return configRepo.findByConfigKey(key)
                .map(c -> {
                    try {
                        return (Object) objectMapper.readValue(
                                c.getConfigValue(), new TypeReference<List<Map<String, Object>>>() {});
                    } catch (Exception e) {
                        return (Object) List.of();
                    }
                })
                .orElse(List.of());
    }
}
