package com.frenchreader.saved;

/** What the popup sends to save an entry: the lookup answer plus its context. */
public record SavedRequest(String lemma, String pos, String meaning, String register,
                           String gloss, String note, String word, String sentence,
                           String articleUrl) {
}
