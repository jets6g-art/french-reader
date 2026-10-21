package com.frenchreader.lookup;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.frenchreader.cache.FileBackedCache;

/**
 * Cache in front of the Claude lookup, keyed on the clicked word's surface form, never the
 * sentence (that would almost never hit). The lemma is not known until the model answers, so the
 * surface form is the only key available beforehand.
 *
 * Only context-free answers are cached. Phrases and idiom results depend on the sentence, so
 * they always go to the model. Failures are never cached.
 *
 * Known tradeoff: an ambiguous word is answered once and reused (e.g. "porte" as noun vs verb).
 */
@Service
@Primary
class CachingLookupService implements LookupService {

    private static final Logger log = LoggerFactory.getLogger(CachingLookupService.class);

    private final LookupService delegate;
    private final FileBackedCache<LookupResponse> cache;

    CachingLookupService(@Qualifier("claudeLookupService") LookupService delegate,
                         @Qualifier("lookupCache") FileBackedCache<LookupResponse> cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public LookupResponse lookup(LookupRequest request) {
        return lookup(request, null);
    }

    /** A cache hit reports no fields; the caller gets the whole answer back at once. */
    @Override
    public LookupResponse lookup(LookupRequest request, BiConsumer<String, String> onField) {
        String word = request.word().strip();
        boolean singleWord = word.chars().noneMatch(Character::isWhitespace);
        String key = key(word);
        if (singleWord) {
            var hit = cache.get(key);
            if (hit.isPresent()) {
                log.info("Lookup cache hit: {}", key);
                return hit.get().value();
            }
        }
        LookupResponse result = onField == null ? delegate.lookup(request) : delegate.lookup(request, onField);
        if (singleWord && isContextFree(result)) {
            cache.put(key, result);
        }
        return result;
    }

    static String key(String word) {
        return Normalizer.normalize(word.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'');
    }

    /** A plain one-word entry; an expression or idiom answer is specific to its sentence. */
    private static boolean isContextFree(LookupResponse r) {
        return r.lemma() != null && !r.lemma().isBlank()
                && r.lemma().strip().chars().noneMatch(Character::isWhitespace)
                && (r.pos() == null || !r.pos().toLowerCase(Locale.ROOT).contains("idiom"));
    }
}
