package com.frenchreader.article;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class FrenchTokenizerTest {

    private static List<String> texts(String s) {
        return FrenchTokenizer.tokenize(s, 0).stream().map(Token::text).collect(Collectors.toList());
    }

    @Test
    void splitsElisionButKeepsWholeWords() {
        assertThat(texts("l'entreprise qu'il d'ailleurs"))
                .containsExactly("l'", "entreprise", "qu'", "il", "d'", "ailleurs");
        assertThat(texts("aujourd'hui peut-être a-t-il du aux"))
                .containsExactly("aujourd'hui", "peut-être", "a-t-il", "du", "aux");
    }

    @Test
    void handlesTypographicApostropheAndCapitals() {
        assertThat(texts("L’Heureux jusqu’à Qu’est-ce"))
                .containsExactly("L’", "Heureux", "jusqu’", "à", "Qu’", "est-ce");
    }

    @Test
    void numbersAndPunctuation() {
        assertThat(texts("550,50 $ le 1er mars « oui », 100 millions."))
                .containsExactly("550,50", "$", "le", "1er", "mars", "«", "oui", "»", ",",
                        "100", "millions", ".");
    }

    @Test
    void roundTripsInputExactlyIncludingNbsp() {
        String in = "M. L’Heureux a payé 550,50 $ — « c’est vendu » ! Qu’est-ce que c’est?";
        String out = FrenchTokenizer.tokenize(in, 0).stream()
                .map(t -> t.text() + t.ws()).collect(Collectors.joining());
        assertThat(out).isEqualTo(in);
    }

    @Test
    void indexesStartWhereTold() {
        List<Token> tokens = FrenchTokenizer.tokenize("Bonjour le monde", 10);
        assertThat(tokens).extracting(Token::idx).containsExactly(10, 11, 12);
    }
}
