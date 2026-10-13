package com.frenchreader.article;

/**
 * One word or punctuation mark. {@code idx} is unique and increasing across the whole article;
 * {@code ws} is the exact whitespace following the token (NBSP preserved), so
 * concatenating {@code text + ws} reproduces the source.
 */
public record Token(int idx, String text, String ws) {
}
