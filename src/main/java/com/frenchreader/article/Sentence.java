package com.frenchreader.article;

import java.util.List;

/**
 * {@code paragraph} groups sentences that came from the same source block; {@code kind} is
 * "p", "heading" or "quote" so the frontend can restore the layout.
 */
public record Sentence(int id, int paragraph, String kind, List<Token> tokens) {
}
