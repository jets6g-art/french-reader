package com.frenchreader.article;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.frenchreader.cache.FileBackedCache;

@Service
public class ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);

    private final ArticleFetcher fetcher;
    private final FileBackedCache<Article> cache;
    private final Duration ttl;
    private final RadioCanadaExtractor extractor = new RadioCanadaExtractor();

    ArticleService(ArticleFetcher fetcher,
                   @Qualifier("articleCache") FileBackedCache<Article> cache,
                   @Value("${reader.cache.article-ttl:PT24H}") Duration ttl) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.ttl = ttl;
    }

    /**
     * Fresh cached copy if there is one; otherwise fetch. If the fetch fails and an older copy
     * exists, serve that, so re-reading works while the source is down.
     */
    public Article load(String url) {
        URI uri = SourcePolicy.require(url);
        String key = cacheKey(uri);
        Optional<FileBackedCache.Entry<Article>> cached = cache.get(key);
        if (cached.isPresent() && age(cached.get()).compareTo(ttl) < 0) {
            return cached.get().value();
        }
        try {
            Article fresh = fetchAndExtract(uri);
            cache.put(key, fresh);
            return fresh;
        } catch (ResponseStatusException e) {
            if (cached.isPresent()) {
                log.warn("Source fetch failed ({}); serving cached copy of {}", e.getStatusCode(), key);
                return cached.get().value();
            }
            throw e;
        }
    }

    private Article fetchAndExtract(URI uri) {
        RadioCanadaExtractor.Extracted extracted = extractor.extract(fetcher.fetch(uri));
        if (extracted.blocks().isEmpty()) {
            // Page loaded but our selectors found nothing: markup changed, or not an article page.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No article content found at that URL");
        }
        return new Article(extracted.title(), SourcePolicy.SOURCE_NAME, uri.toString(),
                toSentences(extracted.blocks()));
    }

    private static Duration age(FileBackedCache.Entry<Article> entry) {
        return Duration.ofMillis(System.currentTimeMillis() - entry.storedAtMillis());
    }

    /** Same article however it was typed: host case and #fragment don't matter. */
    static String cacheKey(URI uri) {
        return "https://" + uri.getHost().toLowerCase() + uri.getRawPath()
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
    }

    static List<Sentence> toSentences(List<RadioCanadaExtractor.Block> blocks) {
        List<Sentence> sentences = new ArrayList<>();
        int idx = 0;
        int paragraph = 0;
        for (RadioCanadaExtractor.Block block : blocks) {
            List<Token> tokens = FrenchTokenizer.tokenize(block.text(), idx);
            idx += tokens.size();
            for (List<Token> s : SentenceSplitter.split(tokens)) {
                sentences.add(new Sentence(sentences.size(), paragraph, block.kind(), s));
            }
            paragraph++;
        }
        return sentences;
    }
}
