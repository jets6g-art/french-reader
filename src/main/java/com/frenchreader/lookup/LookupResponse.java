package com.frenchreader.lookup;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Also the output schema handed to Claude: the descriptions below are the field-level
 * instructions the model sees, so edit them to tune the answers.
 */
public record LookupResponse(
        @JsonPropertyDescription("Dictionary form: infinitive for verbs, masculine singular for nouns and "
                + "adjectives. If the clicked word is part of an idiom or fixed expression in the "
                + "sentence, the whole expression (e.g. \"mettre en œuvre\").")
        String lemma,
        @JsonPropertyDescription("Part of speech in English, lowercase, e.g. \"verb\", \"noun (f.)\", "
                + "\"adjective\", \"adverb\", \"preposition\", \"conjunction\", \"idiom\".")
        String pos,
        @JsonPropertyDescription("What it means in THIS sentence, in one sentence of simple French that "
                + "an intermediate learner understands. For an idiom, explain the whole expression.")
        String meaning,
        @JsonPropertyDescription("Register in French, e.g. \"courant\", \"soutenu\", \"familier\", "
                + "\"populaire\", \"technique\", \"juridique\".")
        String register,
        @JsonPropertyDescription("Short English translation, 1 to 5 words.")
        String gloss,
        @JsonPropertyDescription("Short grammar note in English (one or two short sentences) useful for "
                + "speaking: tense, mood and person for verbs (e.g. \"conditional present, 3rd person "
                + "plural\"), gender and number, agreement, the preposition that follows, common pitfalls.")
        String note) {
}
