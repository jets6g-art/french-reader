package com.frenchreader.lookup;

/** {@code word} may be a single word or a drag-selected phrase; {@code sentence} is its context. */
public record LookupRequest(String word, String sentence, String articleUrl) {
}
