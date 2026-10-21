package com.frenchreader.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class JsonStringFieldsTest {

    private static final String REPLY = "{\"lemma\":\"mettre en œuvre\",\"pos\":\"idiom\","
            + "\"meaning\":\"Dire \\\"faire\\\" \\u00e0 la ligne\\nsuivante.\",\"gloss\":\"to implement\"}";

    private static List<String> feedInChunks(String json, int size) {
        List<String> seen = new ArrayList<>();
        JsonStringFields fields = new JsonStringFields((k, v) -> seen.add(k + "=" + v));
        for (int i = 0; i < json.length(); i += size) {
            fields.feed(json.substring(i, Math.min(json.length(), i + size)));
        }
        return seen;
    }

    @Test
    void reportsEachFieldOnceItsClosingQuoteArrives() {
        List<String> seen = new ArrayList<>();
        JsonStringFields fields = new JsonStringFields((k, v) -> seen.add(k + "=" + v));

        fields.feed("{\"lemma\":\"ains");
        assertThat(seen).isEmpty();
        fields.feed("i\",\"pos\":\"adv");
        assertThat(seen).containsExactly("lemma=ainsi");
        fields.feed("erb\"}");
        assertThat(seen).containsExactly("lemma=ainsi", "pos=adverb");
    }

    @Test
    void decodesEscapesAndGivesTheSameResultForAnyChunking() {
        List<String> expected = List.of("lemma=mettre en œuvre", "pos=idiom",
                "meaning=Dire \"faire\" à la ligne\nsuivante.", "gloss=to implement");
        for (int size : new int[] {1, 2, 3, 7, 1000}) {
            assertThat(feedInChunks(REPLY, size)).as("chunk size " + size).isEqualTo(expected);
        }
    }

    @Test
    void ignoresNonStringValues() {
        assertThat(feedInChunks("{\"n\":12,\"ok\":true,\"x\":null,\"a\":\"b\"}", 2)).containsExactly("a=b");
    }
}
