package com.frenchreader.article;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class SentenceSplitterTest {

    private static List<String> split(String s) {
        return SentenceSplitter.split(FrenchTokenizer.tokenize(s, 0)).stream()
                .map(sent -> sent.stream().map(t -> t.text() + t.ws()).collect(Collectors.joining()).strip())
                .collect(Collectors.toList());
    }

    @Test
    void doesNotSplitAfterAbbreviationsOrInitials() {
        assertThat(split("M. L’Heureux est venu. Il est parti. Mme Roy aussi. J. Trudeau a parlé."))
                .containsExactly("M. L’Heureux est venu.", "Il est parti.", "Mme Roy aussi.",
                        "J. Trudeau a parlé.");
    }

    @Test
    void keepsClosingQuoteWithItsSentence() {
        assertThat(split("Il a dit « Non. » puis il est parti. Fin"))
                .containsExactly("Il a dit « Non. » puis il est parti.", "Fin");
        assertThat(split("Il a dit « Non! » Elle est partie."))
                .containsExactly("Il a dit « Non! »", "Elle est partie.");
    }

    @Test
    void lowercaseAfterQuestionMarkDoesNotSplit() {
        assertThat(split("Pourquoi? parce que. Voilà.")).containsExactly("Pourquoi? parce que.", "Voilà.");
    }

    @Test
    void noSplitInsideDottedTokens() {
        assertThat(split("Voir radio-canada.ca pour Plus. Fin."))
                .containsExactly("Voir radio-canada.ca pour Plus.", "Fin.");
    }

    @Test
    void textWithoutTerminatorIsOneSentence() {
        assertThat(split("Un sous-titre sans point")).containsExactly("Un sous-titre sans point");
    }
}
