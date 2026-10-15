package com.frenchreader.saved;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The saved list. Unlike the caches this is user data, so every change is written to disk before
 * the call returns (temp file + move), and an unreadable file is kept as {@code .corrupt} rather
 * than discarded. One entry per lemma: saving the same word or expression twice returns the
 * entry that is already there.
 */
@Component
class SavedStore {

    private static final Logger log = LoggerFactory.getLogger(SavedStore.class);
    private static final TypeReference<List<SavedItem>> LIST = new TypeReference<>() {
    };

    /** {@code created} is false when the lemma was already saved and the existing entry is returned. */
    record Added(SavedItem item, boolean created) {
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SavedItem> items = new LinkedHashMap<>(); // by id, oldest first

    @Autowired
    SavedStore(@Value("${reader.saved.file:data/saved/words.json}") String file) {
        this(Path.of(file));
    }

    /** A null path keeps everything in memory (tests). */
    SavedStore(Path file) {
        this.file = file;
        load();
    }

    /** Newest first. */
    synchronized List<SavedItem> list() {
        List<SavedItem> newestFirst = new ArrayList<>(items.values());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    synchronized Added add(SavedRequest r) {
        String key = lemmaKey(r.lemma());
        Optional<SavedItem> existing = items.values().stream()
                .filter(i -> lemmaKey(i.lemma()).equals(key)).findFirst();
        if (existing.isPresent()) {
            return new Added(existing.get(), false);
        }
        SavedItem item = new SavedItem(UUID.randomUUID().toString(), r.lemma().strip(),
                nz(r.pos()), nz(r.meaning()), nz(r.register()), nz(r.gloss()), nz(r.note()),
                nz(r.word()), nz(r.sentence()), nz(r.articleUrl()), Instant.now().toString());
        items.put(item.id(), item);
        try {
            persist();
        } catch (RuntimeException e) {
            items.remove(item.id()); // don't claim it is saved when it is not
            throw e;
        }
        return new Added(item, true);
    }

    synchronized boolean remove(String id) {
        SavedItem removed = items.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            persist();
        } catch (RuntimeException e) {
            items.put(removed.id(), removed);
            throw e;
        }
        return true;
    }

    static String lemmaKey(String lemma) {
        return Normalizer.normalize(lemma == null ? "" : lemma.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    private void persist() {
        if (file == null) {
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(items.values()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Could not write saved list {}: {}", file, e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write the saved list to disk");
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            for (SavedItem item : mapper.readValue(file.toFile(), LIST)) {
                items.put(item.id(), item);
            }
            log.info("Loaded {} saved entries from {}", items.size(), file);
        } catch (IOException | RuntimeException e) {
            items.clear();
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt");
            log.error("Saved list {} is unreadable ({}); keeping it as {} and starting empty",
                    file, e.toString(), aside);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // leave it in place
            }
        }
    }
}
