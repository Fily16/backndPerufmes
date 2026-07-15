package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.MediaImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MediaImageRepository extends JpaRepository<MediaImage, Long> {

    /** Resumen sin el payload base64 (la lista de la galeria no debe pesar megas). */
    interface MediaSummary {
        Long getId();
        String getName();
        String getContentType();
        Integer getSizeBytes();
        LocalDateTime getCreatedAt();
    }

    List<MediaSummary> findAllByOrderByCreatedAtDesc();
}
