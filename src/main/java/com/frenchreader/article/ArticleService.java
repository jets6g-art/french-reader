package com.frenchreader.article;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
    private final RadioCanadaExtractor radioCanada = new RadioCanadaExtractor();
    private final GenericExtractor generic = new GenericExtractor();

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
            return withTitleTokens(cached.get().value());
        }
        try {
            Article fresh = fetchAndExtract(uri);
            cache.put(key, fresh);
            return fresh;
        } catch (ResponseStatusException e) {
            if (cached.isPresent()) {
                log.warn("Source fetch failed ({}); serving cached copy of {}", e.getStatusCode(), key);
                return withTitleTokens(cached.get().value());
            }
            throw e;
        }
    }

    private Article fetchAndExtract(URI uri) {
        Document doc = fetcher.fetch(uri);
        RadioCanadaExtractor.Extracted extracted = SourcePolicy.isRadioCanada(uri) ? radioCanada.extract(doc) : null;
        if (extracted == null || extracted.blocks().isEmpty()) {
            // Other sites, and Radio-Canada pages not laid out like a news story.
            extracted = generic.extract(doc);
        }
        if (extracted.blocks().isEmpty()) {
            // Page loaded but no readable text was found: not an article page, or rendered by script.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No article content found at that URL");
        }
        List<Token> titleTokens = FrenchTokenizer.tokenize(extracted.title(), 0);
        return new Article(extracted.title(), titleTokens, sourceName(uri, doc), uri.toString(),
                toSentences(extracted.blocks(), titleTokens.size()));
    }

    /** The site's own name when it declares one, else its host without "www.". */
    static String sourceName(URI uri, Document doc) {
        if (SourcePolicy.isRadioCanada(uri)) {
            return "Radio-Canada";
        }
        Element site = doc.selectFirst("meta[property=og:site_name]");
        if (site != null && !site.attr("content").isBlank()) {
            return site.attr("content").strip();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** Copies cached before titleTokens existed get them now, without a refetch. */
    private static Article withTitleTokens(Article a) {
        if (a.titleTokens() != null) {
            return a;
        }
        return new Article(a.title(), FrenchTokenizer.tokenize(a.title(), 0), a.source(), a.url(), a.sentences());
    }

    private static Duration age(FileBackedCache.Entry<Article> entry) {
        return Duration.ofMillis(System.currentTimeMillis() - entry.storedAtMillis());
    }

    /** Same article however it was typed: scheme and host case, a default port and #fragment don't matter. */
    static String cacheKey(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || port == ("https".equals(scheme) ? 443 : 80);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port)
                + uri.getRawPath()
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
    }

    static List<Sentence> toSentences(List<RadioCanadaExtractor.Block> blocks, int firstIdx) {
        List<Sentence> sentences = new ArrayList<>();
        int idx = firstIdx;
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
