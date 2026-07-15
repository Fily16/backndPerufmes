package org.example.backendbvaberiaperfumes.controller;

import org.example.backendbvaberiaperfumes.model.MediaImage;
import org.example.backendbvaberiaperfumes.repository.ConsolidadoRepository;
import org.example.backendbvaberiaperfumes.repository.MediaImageRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Galeria de imagenes (banners de consolidados). Guardadas en la BD porque el
 * hosting no tiene disco persistente. Lectura publica con cache inmutable
 * (una imagen nunca se edita: se sube otra); administracion bajo /api/admin.
 */
@RestController
public class MediaController {

    /** Tope del payload base64 (~800KB). El admin comprime en el navegador a ~150KB. */
    private static final int MAX_BASE64_LENGTH = 1_100_000;

    private final MediaImageRepository mediaRepo;
    private final ConsolidadoRepository consolidadoRepo;

    public MediaController(MediaImageRepository mediaRepo, ConsolidadoRepository consolidadoRepo) {
        this.mediaRepo = mediaRepo;
        this.consolidadoRepo = consolidadoRepo;
    }

    /** Publico: sirve los bytes de la imagen con cache agresivo. */
    @GetMapping("/api/media/{id}")
    public ResponseEntity<byte[]> serve(@PathVariable Long id) {
        MediaImage img = mediaRepo.findById(id).orElse(null);
        if (img == null || img.getDataBase64() == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(img.getDataBase64());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        MediaType type = MediaType.IMAGE_JPEG;
        try {
            if (img.getContentType() != null) type = MediaType.parseMediaType(img.getContentType());
        } catch (Exception ignored) { }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(bytes);
    }

    /** Admin: lista de la galeria SIN el payload (solo resumenes). */
    @GetMapping("/api/admin/media")
    public List<MediaImageRepository.MediaSummary> list() {
        return mediaRepo.findAllByOrderByCreatedAtDesc();
    }

    /** Formatos permitidos: SVG queda FUERA a proposito (se sirve desde nuestro dominio
     *  y un <svg onload=...> seria XSS almacenado). */
    private static final java.util.Set<String> ALLOWED_TYPES =
            java.util.Set.of("image/webp", "image/jpeg", "image/png");

    /** Admin: sube una imagen como data URL ("data:image/webp;base64,...."). */
    @PostMapping("/api/admin/media")
    public ResponseEntity<?> upload(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) name = "imagen";
        String dataUrl = body.get("dataUrl");
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Se espera un data URL de imagen."));
        }
        int comma = dataUrl.indexOf(',');
        int semi = dataUrl.indexOf(';');
        if (comma < 0 || semi < 0 || semi > comma) {
            return ResponseEntity.badRequest().body(Map.of("message", "Data URL mal formado."));
        }
        String contentType = dataUrl.substring(5, semi);
        if (!ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Formato no permitido (" + contentType + "). Usa JPG, PNG o WebP."));
        }
        String base64 = dataUrl.substring(comma + 1);
        if (base64.length() > MAX_BASE64_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "La imagen supera el tamaño máximo (~800KB). Usa una más liviana."));
        }
        try {
            Base64.getDecoder().decode(base64); // valida que sea base64 real
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "El contenido no es base64 válido."));
        }
        MediaImage img = new MediaImage();
        img.setName(name.length() > 160 ? name.substring(0, 160) : name);
        img.setContentType(contentType);
        img.setDataBase64(base64);
        img.setSizeBytes((int) Math.ceil(base64.length() * 3.0 / 4.0));
        img = mediaRepo.save(img);
        return ResponseEntity.ok(Map.of(
                "id", img.getId(),
                "name", img.getName(),
                "contentType", img.getContentType(),
                "sizeBytes", img.getSizeBytes()));
    }

    /** Admin: elimina una imagen (bloqueado si un consolidado la esta usando). */
    @DeleteMapping("/api/admin/media/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!mediaRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        if (consolidadoRepo.existsByImageMediaId(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "La imagen está en uso por un consolidado. Cámbiala primero."));
        }
        mediaRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Imagen eliminada."));
    }
}
