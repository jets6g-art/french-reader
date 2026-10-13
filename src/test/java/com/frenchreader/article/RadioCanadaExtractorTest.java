package com.frenchreader.article;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/** Synthetic page mirroring the structure observed on a live ici.radio-canada.ca/nouvelle page. */
class RadioCanadaExtractorTest {

    private static final String HTML = """
            <html><head><title>Titre | Radio-Canada.ca</title></head><body>
            <header><p>nav junk</p></header>
            <h1 class="x"><span>Quand vous magasinez</span></h1>
            <div class="flow-root"><div class="sc-e5s2wj-10 hBoNfl">
              <p class="sc-e5s2wj-8">Le <em>lede</em>&nbsp;ici.</p></div></div>
            <figure><p class="mb-0">Caption outside</p></figure>
            <div class="flow-root"><div class="sc-e5s2wj-10 other">
              <p class="sc-e5s2wj-8">Para&nbsp;un.</p>
              <div aria-hidden="true" data-ml-tracking-type="ContentMarkerCapture"></div>
              <h2 class="sc-e5s2wj-6"><em>La</em> sous-titre</h2>
              <div class="sc-e5s2wj-0"><div><p class="mb-0">Photo caption</p>
                <p class="image-credit">Photo : someone</p></div></div>
              <blockquote><p>Citation.</p><footer>Auteur</footer></blockquote>
              <div class="sc-e5s2wj-5"><div class="ad"><p>Ad text</p></div></div>
              <div class="framed"><h2>Encadré</h2><p>Définition.</p></div>
              <h2>&Agrave; lire et &agrave; regarder&nbsp;:</h2><ul><li><a href="/x">Related</a></li></ul>
            </div></div>
            <aside><p>related link</p></aside>
            </body></html>
            """;

    @Test
    void extractsTitleAndOnlyReadableBlocksInOrder() {
        RadioCanadaExtractor.Extracted ex = new RadioCanadaExtractor().extract(Jsoup.parse(HTML));

        assertThat(ex.title()).isEqualTo("Quand vous magasinez");
        assertThat(ex.blocks()).extracting(RadioCanadaExtractor.Block::kind)
                .containsExactly("p", "p", "heading", "quote", "heading", "p");
        assertThat(ex.blocks()).extracting(RadioCanadaExtractor.Block::text).containsExactly(
                "Le lede ici.", "Para un.", "La sous-titre", "Citation.", "Encadré", "Définition.");
    }

    @Test
    void pageWithoutArticleBodyYieldsNoBlocks() {
        RadioCanadaExtractor.Extracted ex = new RadioCanadaExtractor()
                .extract(Jsoup.parse("<html><body><h1>Hi</h1><p>stray</p></body></html>"));
        assertThat(ex.blocks()).isEmpty();
    }
}
