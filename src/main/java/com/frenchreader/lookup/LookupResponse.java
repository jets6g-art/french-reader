package com.frenchreader.lookup;

public record LookupResponse(String lemma, String pos, String meaning, String register,
                             String gloss, String note) {
}
