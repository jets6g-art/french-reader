package com.frenchreader.cache;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Small LRU cache that survives restarts. Entries live in memory; changes are written to one
 * JSON file a couple of seconds after the last put (and on shutdown), via a temp file + move so
 * a crash never leaves a half-written file. Pass a null file for a memory-only cache.
 * Sized for personal use: a few thousand small entries or a hundred articles.
 */
public final class FileBackedCache<V> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileBackedCache.class);
    private static final long FLUSH_DELAY_SECONDS = 2;

    public record Entry<V>(long storedAtMillis, V value) {
    }

    private final Path file;
    private final int maxEntries;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JavaType mapType;
    private final LinkedHashMap<String, Entry<V>> map;
    private final ScheduledExecutorService flusher;
    private boolean dirty;
    private boolean flushScheduled;
    private boolean closed;

    public FileBackedCache(Path file, int maxEntries, Class<V> valueType) {
        this.file = file;
        this.maxEntries = maxEntries;
        JavaType entryType = mapper.getTypeFactory().constructParametricType(Entry.class, valueType);
        this.mapType = mapper.getTypeFactory().constructMapType(
                LinkedHashMap.class, mapper.constructType(String.class), entryType);
        // access order: get() refreshes an entry, the eldest is evicted first
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<V>> eldest) {
                return size() > FileBackedCache.this.maxEntries;
            }
        };
        this.flusher = file == null ? null : Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-flush");
            t.setDaemon(true);
            return t;
        });
        load();
    }

    public synchronized Optional<Entry<V>> get(String key) {
        return Optional.ofNullable(map.get(key));
    }

    public synchronized void put(String key, V value) {
        map.put(key, new Entry<>(System.currentTimeMillis(), value));
        dirty = true;
        if (flusher != null && !flushScheduled && !closed) {
            flushScheduled = true;
            flusher.schedule(this::flush, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    public synchronized int size() {
        return map.size();
    }

    /** Writes pending changes now and stops the background flusher. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        if (flusher != null) {
            flusher.shutdownNow();
        }
        flush();
    }

    private void flush() {
        Map<String, Entry<V>> snapshot;
        synchronized (this) {
            flushScheduled = false;
            if (file == null || !dirty) {
                return;
            }
            dirty = false;
            snapshot = new LinkedHashMap<>(map); // iteration order = LRU order, so reload keeps it
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            mapper.writerFor(mapType).writeValue(tmp.toFile(), snapshot);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not save cache {}: {}", file, e.toString());
            synchronized (this) {
                dirty = true; // retry on the next put/close
            }
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            Map<String, Entry<V>> loaded = mapper.readValue(file.toFile(), mapType);
            loaded.forEach(map::put);
            log.info("Loaded {} cached entries from {}", map.size(), file);
        } catch (IOException | RuntimeException e) {
            // A cache must never stop the app from starting: set the bad file aside and begin empty.
            log.warn("Ignoring unreadable cache {}: {}", file, e.toString());
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // leave it; the next successful flush overwrites it
            }
            map.clear();
        }
    }
}
