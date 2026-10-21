package com.frenchreader.lookup;

import java.util.function.BiConsumer;

/**
 * Incremental reader for the model's JSON reply: fed text chunks as they stream in, it reports each
 * top-level string field the moment its closing quote arrives. Only flat objects of string values
 * are understood, which is all LookupResponse is; anything else is ignored here and caught by the
 * real parse of the complete text afterwards.
 */
final class JsonStringFields {

    private enum State { OUTSIDE, KEY, AFTER_KEY, VALUE }

    private final BiConsumer<String, String> onField;
    private final StringBuilder buf = new StringBuilder();
    private final StringBuilder unicode = new StringBuilder();
    private State state = State.OUTSIDE;
    private String key = "";
    private boolean escape;
    private boolean inUnicode;

    JsonStringFields(BiConsumer<String, String> onField) {
        this.onField = onField;
    }

    void feed(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            accept(chunk.charAt(i));
        }
    }

    private void accept(char c) {
        switch (state) {
            case OUTSIDE -> {
                if (c == '"') {
                    start(State.KEY);
                }
            }
            case AFTER_KEY -> {
                if (c == '"') {
                    start(State.VALUE);
                } else if (c == ',' || c == '}') {
                    state = State.OUTSIDE; // a non-string value: skipped
                }
            }
            case KEY, VALUE -> inString(c);
        }
    }

    private void start(State next) {
        state = next;
        buf.setLength(0);
        escape = false;
        inUnicode = false;
    }

    private void inString(char c) {
        if (inUnicode) {
            unicode.append(c);
            if (unicode.length() == 4) {
                try {
                    buf.append((char) Integer.parseInt(unicode.toString(), 16));
                } catch (NumberFormatException e) {
                    buf.append('�'); // malformed; the full parse of the reply will reject it
                }
                inUnicode = false;
            }
        } else if (escape) {
            escape = false;
            switch (c) {
                case 'n' -> buf.append('\n');
                case 't' -> buf.append('\t');
                case 'r' -> buf.append('\r');
                case 'b' -> buf.append('\b');
                case 'f' -> buf.append('\f');
                case 'u' -> {
                    inUnicode = true;
                    unicode.setLength(0);
                }
                default -> buf.append(c); // \" \\ \/
            }
        } else if (c == '\\') {
            escape = true;
        } else if (c == '"') {
            if (state == State.KEY) {
                key = buf.toString();
                state = State.AFTER_KEY;
            } else {
                state = State.OUTSIDE;
                onField.accept(key, buf.toString());
            }
        } else {
            buf.append(c);
        }
    }
}
