package com.frenchreader.article;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits a token stream into sentences. A sentence ends at . ! ? or an ellipsis (plus any
 * closing quotes/brackets after it) when whitespace follows and the next token looks like a
 * sentence start. Abbreviations ("M.", "Mme") and initials do not end sentences.
 */
final class SentenceSplitter {

    private static final Set<String> TERMINATORS = Set.of(".", "!", "?", "…");
    private static final String CLOSERS = "»”\")]’'";
    private static final String OPENERS = "«“\"([—–¿¡";
    private static final Set<String> ABBREVIATIONS = Set.of(
            "m", "mm", "mme", "mmes", "mlle", "mlles", "me", "mes", "dr", "dre", "pr", "pre",
            "st", "ste", "mgr", "cie", "vs", "no", "art", "p", "ex", "env", "av", "boul");

    private SentenceSplitter() {
    }

    static List<List<Token>> split(List<Token> tokens) {
        List<List<Token>> sentences = new ArrayList<>();
        int start = 0;
        int n = tokens.size();
        for (int i = 0; i < n; i++) {
            if (!TERMINATORS.contains(tokens.get(i).text())) {
                continue;
            }
            int end = i;
            while (end + 1 < n && isTerminatorOrCloser(tokens.get(end + 1).text())) {
                end++;
            }
            boolean lastInBlock = end == n - 1;
            if (lastInBlock || (!tokens.get(end).ws().isEmpty()
                    && !isAbbreviation(tokens, i, end)
                    && startsSentence(tokens.get(end + 1).text()))) {
                sentences.add(new ArrayList<>(tokens.subList(start, end + 1)));
                start = end + 1;
            }
            i = end;
        }
        if (start < n) {
            sentences.add(new ArrayList<>(tokens.subList(start, n)));
        }
        return sentences;
    }

    private static boolean isTerminatorOrCloser(String t) {
        return TERMINATORS.contains(t) || (t.length() == 1 && CLOSERS.contains(t));
    }

    /** "M." / "Mme." / initial "J." — a lone "." right after such a token. */
    private static boolean isAbbreviation(List<Token> tokens, int dot, int end) {
        if (dot != end || !tokens.get(dot).text().equals(".") || dot == 0) {
            return false;
        }
        String prev = tokens.get(dot - 1).text();
        boolean initial = prev.length() == 1 && Character.isUpperCase(prev.charAt(0));
        return initial || ABBREVIATIONS.contains(prev.toLowerCase());
    }

    private static boolean startsSentence(String next) {
        int cp = next.codePointAt(0);
        return Character.isUpperCase(cp) || Character.isDigit(cp)
                || (next.length() == 1 && OPENERS.indexOf(cp) >= 0);
    }
}
