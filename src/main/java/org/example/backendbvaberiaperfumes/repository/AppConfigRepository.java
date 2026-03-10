package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AppConfigRepository extends JpaRepository<AppConfig, Long> {
    Optional<AppConfig> findByConfigKey(String configKey);
}
