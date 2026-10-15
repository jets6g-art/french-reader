package com.frenchreader.saved;

/**
 * One saved word or expression: the lookup answer plus where it was met. {@code word} is the
 * surface form that was clicked, {@code sentence} the context it appeared in, {@code savedAt}
 * an ISO-8601 instant.
 */
public record SavedItem(String id, String lemma, String pos, String meaning, String register,
                        String gloss, String note, String word, String sentence,
                        String articleUrl, String savedAt) {
}
