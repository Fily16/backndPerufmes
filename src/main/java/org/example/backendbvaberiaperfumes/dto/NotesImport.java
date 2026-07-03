package org.example.backendbvaberiaperfumes.dto;

import java.util.List;

/**
 * Item de importacion masiva de notas olfativas (generado por tools/build_notes_dataset.py).
 * Las notas y estaciones llegan como arrays de slugs y se guardan unidas por coma.
 */
public class NotesImport {
    private Long id;
    private List<String> notesTop;
    private List<String> notesMiddle;
    private List<String> notesBase;
    private String family;
    private String occasion;
    private List<String> seasons;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public List<String> getNotesTop() {
        return notesTop;
    }

    public void setNotesTop(List<String> notesTop) {
        this.notesTop = notesTop;
    }

    public List<String> getNotesMiddle() {
        return notesMiddle;
    }

    public void setNotesMiddle(List<String> notesMiddle) {
        this.notesMiddle = notesMiddle;
    }

    public List<String> getNotesBase() {
        return notesBase;
    }

    public void setNotesBase(List<String> notesBase) {
        this.notesBase = notesBase;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getOccasion() {
        return occasion;
    }

    public void setOccasion(String occasion) {
        this.occasion = occasion;
    }

    public List<String> getSeasons() {
        return seasons;
    }

    public void setSeasons(List<String> seasons) {
        this.seasons = seasons;
    }
}
