package com.frenchreader.article;

import java.util.List;

public record Article(String title, String source, String url, List<Sentence> sentences) {
}
