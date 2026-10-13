package com.frenchreader.article;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ArticleService {

    private final ArticleFetcher fetcher;
    private final RadioCanadaExtractor extractor = new RadioCanadaExtractor();

    ArticleService(ArticleFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public Article load(String url) {
        URI uri = SourcePolicy.require(url);
        RadioCanadaExtractor.Extracted extracted = extractor.extract(fetcher.fetch(uri));
        if (extracted.blocks().isEmpty()) {
            // Page loaded but our selectors found nothing: markup changed, or not an article page.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No article content found at that URL");
        }
        return new Article(extracted.title(), SourcePolicy.SOURCE_NAME, uri.toString(),
                toSentences(extracted.blocks()));
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
