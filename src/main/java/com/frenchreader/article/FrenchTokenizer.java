package com.frenchreader.article;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * French-aware tokenizer. Elided clitics split off with their apostrophe
 * (l'entreprise -> l' | entreprise, qu'il -> qu' | il) while aujourd'hui, peut-être and
 * est-il stay whole. Every token keeps its trailing whitespace, so text + ws over all tokens
 * rebuilds the input.
 */
final class FrenchTokenizer {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Only genuine elision forms; the whole letter run before the apostrophe must be one of these. */
    private static final Pattern ELISION = Pattern.compile(
            "(?:[cdjlmnst]|qu|jusqu|lorsqu|puisqu|quoiqu|presqu)['\u2019](?=\\p{L})", FLAGS);
    private static final Pattern NUMBER = Pattern.compile(
            "\\d+(?:[.,]\\d+)*(?:(?:ers|ères|ère|res|re|er|es|e|ème)(?!\\p{L}))?", FLAGS);
    private static final Pattern WORD = Pattern.compile("\\p{L}+(?:[-'\u2019]\\p{L}+)*");

    private FrenchTokenizer() {
    }

    static List<Token> tokenize(String text, int startIdx) {
        List<Token> tokens = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (isSpace(text.charAt(i))) { // only reachable for leading whitespace
                i++;
                continue;
            }
            int end = tokenEnd(text, i);
            int wsEnd = end;
            while (wsEnd < n && isSpace(text.charAt(wsEnd))) {
                wsEnd++;
            }
            tokens.add(new Token(startIdx + tokens.size(), text.substring(i, end), text.substring(end, wsEnd)));
            i = wsEnd;
        }
        return tokens;
    }

    private static int tokenEnd(String text, int i) {
        for (Pattern p : new Pattern[] {ELISION, NUMBER, WORD}) {
            Matcher m = p.matcher(text).region(i, text.length());
            if (m.lookingAt()) {
                return m.end();
            }
        }
        return i + Character.charCount(text.codePointAt(i)); // single punctuation mark / symbol
    }

    /** Includes NBSP (U+00A0) and narrow NBSP (U+202F), which Character.isWhitespace excludes. */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
