package com.frenchreader.article;

import java.util.List;

/**
 * {@code titleTokens} is the title run through the same tokenizer as the body, so the frontend can
 * make title words clickable. Articles cached before this field existed have it null; see
 * {@link ArticleService#load}.
 */
public record Article(String title, List<Token> titleTokens, String source, String url, List<Sentence> sentences) {
}
