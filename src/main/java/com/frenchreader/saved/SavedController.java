package com.frenchreader.saved;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side so the PC and the iPad share one list. There is no login: like the rest of the app
 * this assumes a single user on a trusted local network.
 */
@RestController
@RequestMapping("/api/v1/saved")
public class SavedController {

    static final int MAX_FIELD_CHARS = 2000;

    private final SavedStore store;

    SavedController(SavedStore store) {
        this.store = store;
    }

    @GetMapping
    public List<SavedItem> list() {
        return store.list();
    }

    /** 201 for a new entry, 200 with the existing one when that lemma is already saved. */
    @PostMapping
    public ResponseEntity<SavedItem> save(@RequestBody SavedRequest request) {
        if (request.lemma() == null || request.lemma().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lemma is required");
        }
        if (request.meaning() == null || request.meaning().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "meaning is required");
        }
        for (String value : new String[] {request.lemma(), request.pos(), request.meaning(), request.register(),
                request.gloss(), request.note(), request.word(), request.sentence(), request.articleUrl()}) {
            if (value != null && value.length() > MAX_FIELD_CHARS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "fields are limited to " + MAX_FIELD_CHARS + " characters");
            }
        }
        SavedStore.Added added = store.add(request);
        return ResponseEntity.status(added.created() ? HttpStatus.CREATED : HttpStatus.OK).body(added.item());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!store.remove(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved entry with that id");
        }
        return ResponseEntity.noContent().build();
    }
}
