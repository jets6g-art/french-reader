package com.frenchreader.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedCacheTest {

    record Item(String text, int n) {
    }

    @TempDir
    Path dir;

    @Test
    void getReturnsWhatWasPut() {
        try (var cache = new FileBackedCache<>(null, 10, Item.class)) {
            assertThat(cache.get("a")).isEmpty();
            cache.put("a", new Item("bonjour", 1));
            assertThat(cache.get("a")).get().extracting(FileBackedCache.Entry::value)
                    .isEqualTo(new Item("bonjour", 1));
            assertThat(cache.get("a").get().storedAtMillis()).isCloseTo(System.currentTimeMillis(),
                    org.assertj.core.data.Offset.offset(5_000L));
        }
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        try (var cache = new FileBackedCache<>(null, 2, Item.class)) {
            cache.put("a", new Item("a", 1));
            cache.put("b", new Item("b", 2));
            cache.get("a"); // a is now more recent than b
            cache.put("c", new Item("c", 3));

            assertThat(cache.get("b")).isEmpty();
            assertThat(cache.get("a")).isPresent();
            assertThat(cache.get("c")).isPresent();
            assertThat(cache.size()).isEqualTo(2);
        }
    }

    @Test
    void survivesRestartIncludingAccents() {
        Path file = dir.resolve("sub/cache.json"); // directory does not exist yet
        try (var cache = new FileBackedCache<>(file, 10, Item.class)) {
            cache.put("néanmoins", new Item("été œuvre", 7));
        } // close() flushes

        try (var reloaded = new FileBackedCache<>(file, 10, Item.class)) {
            assertThat(reloaded.get("néanmoins")).get().extracting(FileBackedCache.Entry::value)
                    .isEqualTo(new Item("été œuvre", 7));
        }
        assertThat(dir.resolve("sub/cache.json.tmp")).doesNotExist();
    }

    @Test
    void reloadKeepsRecencyOrder() {
        Path file = dir.resolve("cache.json");
        try (var cache = new FileBackedCache<>(file, 2, Item.class)) {
            cache.put("a", new Item("a", 1));
            cache.put("b", new Item("b", 2));
            cache.get("a"); // b is the eldest
        }
        try (var reloaded = new FileBackedCache<>(file, 2, Item.class)) {
            reloaded.put("c", new Item("c", 3));
            assertThat(reloaded.get("b")).isEmpty();
            assertThat(reloaded.get("a")).isPresent();
        }
    }

    @Test
    void writesInTheBackgroundWithoutClose() throws Exception {
        Path file = dir.resolve("bg.json");
        try (var cache = new FileBackedCache<>(file, 10, Item.class)) {
            cache.put("a", new Item("a", 1));
            long deadline = System.currentTimeMillis() + 6_000;
            while (!Files.exists(file) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(file).exists();
        }
    }

    @Test
    void corruptFileIsSetAsideAndCacheStartsEmpty() throws Exception {
        Path file = dir.resolve("cache.json");
        Files.writeString(file, "{ this is not json");

        try (var cache = new FileBackedCache<>(file, 10, Item.class)) {
            assertThat(cache.size()).isZero();
            cache.put("a", new Item("a", 1));
        }
        assertThat(dir.resolve("cache.json.corrupt")).exists();
        try (var reloaded = new FileBackedCache<>(file, 10, Item.class)) {
            assertThat(reloaded.get("a")).isPresent();
        }
    }
}
