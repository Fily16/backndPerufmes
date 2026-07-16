package org.example.backendbvaberiaperfumes.dto;

/**
 * Candidata de imagen para un perfume: una de las N finales que el algoritmo de
 * búsqueda consideró (ya filtrada de orígenes bloqueados y ordenada por relevancia).
 * Se serializa tal cual al frontend (revisión visual) y al caché (candidates_json).
 */
public class ImageCandidate {
    public String url;
    public String origin;
    public String title;
    public int score;

    public ImageCandidate() { }

    public ImageCandidate(String url, String origin, String title, int score) {
        this.url = url;
        this.origin = origin;
        this.title = title;
        this.score = score;
    }
}
