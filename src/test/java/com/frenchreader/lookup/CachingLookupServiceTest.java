package com.frenchreader.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.frenchreader.cache.FileBackedCache;

class CachingLookupServiceTest {

    private final AtomicInteger calls = new AtomicInteger();
    private LookupResponse next = plain("toutefois");
    private RuntimeException failure;

    private final LookupService delegate = req -> {
        calls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return next;
    };
    private final CachingLookupService service =
            new CachingLookupService(delegate, new FileBackedCache<>(null, 100, LookupResponse.class));

    private static LookupResponse plain(String lemma) {
        return new LookupResponse(lemma, "adverb", "meaning", "courant", "however", "note", "", "", "", "");
    }

    private static LookupRequest req(String word, String sentence) {
        return new LookupRequest(word, sentence, null);
    }

    @Test
    void sameWordInADifferentSentenceHitsTheCache() {
        LookupResponse first = service.lookup(req("toutefois", "Toutefois, il est parti."));
        LookupResponse second = service.lookup(req("toutefois", "Une toute autre phrase, toutefois."));

        assertThat(calls).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void keyIgnoresCaseSpacingAndApostropheStyle() {
        service.lookup(req("Toutefois", "s1"));
        service.lookup(req("  toutefois ", "s2"));
        assertThat(calls).hasValue(1);

        next = plain("l'");
        service.lookup(req("L’", "s3"));
        service.lookup(req("l'", "s4"));
        assertThat(calls).hasValue(2);
        assertThat(CachingLookupService.key("L’")).isEqualTo("l'");
    }

    @Test
    void differentWordsAreSeparateEntries() {
        service.lookup(req("ainsi", "s"));
        service.lookup(req("néanmoins", "s"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void idiomAnswersAreNotCachedBecauseTheyDependOnTheSentence() {
        next = new LookupResponse("faire partie de", "idiom", "m", "courant", "to be part of", "n", "", "", "", "");
        service.lookup(req("partie", "Ils font partie de l'équipe."));
        service.lookup(req("partie", "Ils font partie de l'équipe."));
        assertThat(calls).hasValue(2);
    }

    @Test
    void aWordAnswerThatAlsoFoundAnIdiomIsNotCached() {
        next = new LookupResponse("partie", "noun (f.)", "m", "courant", "part", "n",
                "faire partie de", "Être un des éléments de.", "to be part of", "Followed by de.");
        service.lookup(req("partie", "Ils font partie de l'équipe."));
        service.lookup(req("partie", "Une partie du budget."));
        assertThat(calls).hasValue(2);
    }

    @Test
    void answersCachedBeforeTheIdiomFieldsExistedStillHit() {
        next = new LookupResponse("ainsi", "adverb", "m", "courant", "thus", "n", null, null, null, null);
        service.lookup(req("ainsi", "s1"));
        service.lookup(req("ainsi", "s2"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void multiWordSelectionsAreNeverCached() {
        next = plain("mettre en œuvre");
        service.lookup(req("mettre en œuvre", "s"));
        service.lookup(req("mettre en œuvre", "s"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void aOneWordClickThatAnswersWithAnExpressionIsNotCached() {
        next = new LookupResponse("mettre en œuvre", "verb", "m", "soutenu", "to implement", "n", "", "", "", "");
        service.lookup(req("mettre", "s"));
        service.lookup(req("mettre", "s"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void failuresAreNotCachedAndPassThrough() {
        failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom");
        assertThatThrownBy(() -> service.lookup(req("ainsi", "s"))).isSameAs(failure);

        failure = null;
        next = plain("ainsi");
        assertThat(service.lookup(req("ainsi", "s")).lemma()).isEqualTo("ainsi");
        assertThat(calls).hasValue(2);
    }
}
