package org.example.backendbvaberiaperfumes.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ThreadLocalRandom;

@Service
public class KeepAliveService {
    private static final Logger log = LoggerFactory.getLogger(KeepAliveService.class);

    @Value("${app.keep-alive.url:}")
    private String keepAliveUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // Se ejecuta cada 7 minutos (420000 ms) como base
    @Scheduled(fixedDelay = 420000, initialDelay = 420000)
    public void ping() {
        if (keepAliveUrl == null || keepAliveUrl.isBlank()) {
            return;
        }

        // Delay random entre 0 y 4 minutos extra (total: 7-11 min entre pings)
        try {
            long randomDelay = ThreadLocalRandom.current().nextLong(0, 240000);
            Thread.sleep(randomDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            restTemplate.getForObject(keepAliveUrl + "/api/products?onlyAvailable=true", String.class);
            log.info("Keep-alive ping OK");
        } catch (Exception e) {
            log.warn("Keep-alive ping failed: {}", e.getMessage());
        }
    }
}
