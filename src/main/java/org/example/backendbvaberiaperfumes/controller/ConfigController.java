package org.example.backendbvaberiaperfumes.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.service.PricingService;
import org.example.backendbvaberiaperfumes.service.nso.NsoGate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final PricingService pricingService;
    private final AppConfigRepository configRepo;
    private final NsoGate nsoGate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigController(PricingService pricingService, AppConfigRepository configRepo, NsoGate nsoGate) {
        this.pricingService = pricingService;
        this.configRepo = configRepo;
        this.nsoGate = nsoGate;
    }

    // Public: return config needed by frontend
    @GetMapping("/public")
    public Map<String, Object> getPublicConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("yapeNumber", pricingService.getYapeNumber());
        cfg.put("exchangeRate", pricingService.getExchangeRate());
        cfg.put("minOrderUsd", pricingService.getMinOrderUsd());
        cfg.put("banners", visibleBanners(readJsonArray("home_banners")));
        cfg.put("promos", visibleBanners(readJsonArray("home_promos")));
        return cfg;
    }

    /**
     * Gate NSO activo: se quitan los banners que llevan a un perfume oculto (linkType "product" con su id), para
     * no anunciar algo que la tienda ya no muestra. Gate apagado: la lista tal cual.
     */
    private Object visibleBanners(Object banners) {
        if (!(banners instanceof List<?> list) || !nsoGate.isActive()) return banners;
        List<Object> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && "product".equals(m.get("linkType"))) {
                Long productId = parseId(m.get("linkValue"));
                if (productId != null && !nsoGate.isPublicId(productId)) continue;
            }
            out.add(o);
        }
        return out;
    }

    private static Long parseId(Object v) {
        if (v == null) return null;
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
