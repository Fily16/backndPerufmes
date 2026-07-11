package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.ImageCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImageCacheRepository extends JpaRepository<ImageCache, Long> {
    Optional<ImageCache> findByCacheKey(String cacheKey);
    List<ImageCache> findByCacheKeyIn(Collection<String> cacheKeys);
}
