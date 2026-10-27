package com.frenchreader.lookup;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Also the output schema handed to Claude: the descriptions below are the field-level
 * instructions the model sees, so edit them to tune the answers.
 *
 * The first six fields always describe the clicked word itself. The idiom fields are filled only
 * when that word is part of an idiom or fixed expression in the sentence; otherwise they are empty
 * (and null in answers cached before they existed).
 */
public record LookupResponse(
        @JsonPropertyDescription("Dictionary form of the clicked word itself: infinitive for verbs, "
                + "masculine singular for nouns and adjectives. Never the whole idiom (that goes in \"idiom\") "
                + "and never another word of it: \"compte\" in \"tenir compte de\" is \"compte\", not \"tenir\".")
        String lemma,
        @JsonPropertyDescription("Part of speech of the clicked word in English, lowercase, e.g. \"verb\", "
                + "\"noun (f.)\", \"adjective\", \"adverb\", \"preposition\", \"conjunction\".")
        String pos,
        @JsonPropertyDescription("What the clicked word itself means in THIS sentence, in one sentence of "
                + "simple French that an intermediate learner understands.")
        String meaning,
        @JsonPropertyDescription("Register of the clicked word in French, e.g. \"courant\", \"soutenu\", "
                + "\"familier\", \"populaire\", \"technique\", \"juridique\".")
        String register,
        @JsonPropertyDescription("Short English translation of the clicked word, 1 to 5 words.")
        String gloss,
        @JsonPropertyDescription("Short grammar note in English (one or two short sentences) about the clicked "
                + "word, useful for speaking: tense, mood and person for verbs (e.g. \"conditional present, "
                + "3rd person plural\"), gender and number, agreement, the preposition that follows, common pitfalls. "
                + "Don't explain the idiom here; the idiom fields do that.")
        String note,
        @JsonPropertyDescription("The idiom, fixed expression or set construction that the clicked word is one "
                + "of the words of, in this sentence, in dictionary form: e.g. \"mettre en marché\" for \"marché\" "
                + "in \"ils mettent en marché leurs produits\", \"ne ... pas\" for \"pas\". Empty string if the "
                + "clicked word is not part of such an expression.")
        String idiom,
        @JsonPropertyDescription("What the whole idiom means in THIS sentence, in one sentence of simple French. "
                + "Empty string if there is no idiom.")
        String idiomMeaning,
        @JsonPropertyDescription("Short English translation of the whole idiom, 1 to 5 words. Empty string if "
                + "there is no idiom.")
        String idiomGloss,
        @JsonPropertyDescription("One short sentence in English on using the idiom when speaking: how it is "
                + "built or what follows it, or a common mistake. Empty string if there is no idiom.")
        String idiomNote) {
}
