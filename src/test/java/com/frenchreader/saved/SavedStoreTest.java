package com.frenchreader.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class SavedStoreTest {

    @TempDir
    Path dir;

    private static SavedRequest req(String lemma) {
        return new SavedRequest(lemma, "verb", "Faire entrer en application.", "soutenu", "to implement",
                "infinitive", "mettre", "Il faut mettre en œuvre le plan.", "https://ici.radio-canada.ca/nouvelle/1/x");
    }

    @Test
    void addsListsNewestFirstAndRemoves() {
        SavedStore store = new SavedStore((Path) null);
        SavedItem a = store.add(req("autoriser")).item();
        SavedItem b = store.add(req("toutefois")).item();

        assertThat(store.list()).extracting(SavedItem::lemma).containsExactly("toutefois", "autoriser");
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(a.savedAt()).isNotBlank();

        assertThat(store.remove(a.id())).isTrue();
        assertThat(store.remove(a.id())).isFalse();
        assertThat(store.list()).extracting(SavedItem::lemma).containsExactly("toutefois");
    }

    @Test
    void savingTheSameLemmaAgainReturnsTheExistingEntry() {
        SavedStore store = new SavedStore((Path) null);
        SavedStore.Added first = store.add(req("Mettre en œuvre"));
        SavedStore.Added again = store.add(req("  mettre en œuvre "));
        SavedStore.Added apostrophe = store.add(req("aujourd’hui"));
        SavedStore.Added straight = store.add(req("aujourd'hui"));

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.item()).isEqualTo(first.item());
        assertThat(apostrophe.created()).isTrue();
        assertThat(straight.created()).isFalse();
        assertThat(store.list()).hasSize(2);
    }

    @Test
    void everyChangeIsOnDiskImmediatelyAndSurvivesRestart() {
        Path file = dir.resolve("saved/words.json"); // directory does not exist yet
        SavedStore store = new SavedStore(file);
        SavedItem kept = store.add(req("mettre en œuvre")).item();
        SavedItem gone = store.add(req("autoriser")).item();
        store.remove(gone.id());

        // no close(): a second instance reads what is already written
        SavedStore reloaded = new SavedStore(file);
        assertThat(reloaded.list()).containsExactly(kept);
        assertThat(reloaded.list().get(0).sentence()).contains("œuvre");
        assertThat(dir.resolve("saved/words.json.tmp")).doesNotExist();
    }

    @Test
    void missingOptionalFieldsBecomeEmptyStrings() {
        SavedStore store = new SavedStore((Path) null);
        SavedItem item = store.add(new SavedRequest("ainsi", null, "Donc.", null, null, null, null, null, null)).item();
        assertThat(item.pos()).isEmpty();
        assertThat(item.sentence()).isEmpty();
    }

    @Test
    void unreadableFileIsKeptAsCorruptAndNeverOverwritten() throws Exception {
        Path file = dir.resolve("words.json");
        Files.writeString(file, "[ this is not json");

        SavedStore store = new SavedStore(file);
        assertThat(store.list()).isEmpty();
        assertThat(dir.resolve("words.json.corrupt")).hasContent("[ this is not json");

        store.add(req("ainsi"));
        assertThat(dir.resolve("words.json.corrupt")).hasContent("[ this is not json");
        assertThat(new SavedStore(file).list()).hasSize(1);
    }

    @Test
    void failedWriteIsReportedAndNotRemembered() throws Exception {
        // a regular file where the parent directory should be makes createDirectories fail
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");
        SavedStore store = new SavedStore(blocker.resolve("words.json"));

        assertThatThrownBy(() -> store.add(req("ainsi")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(500));
        assertThat(store.list()).isEmpty();
    }
}
