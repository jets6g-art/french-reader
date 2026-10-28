package com.frenchreader.article;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class GenericExtractorTest {

    private static final String HTML = """
            <html><head><title>Titre | Le Journal</title></head><body>
            <header><h1>Le Journal</h1><p>Abonnez-vous à notre infolettre quotidienne dès aujourd'hui.</p></header>
            <nav><p>Accueil, Politique, Économie, Culture, Sports et Météo du jour</p></nav>
            <main><article>
              <h1>Le budget est adopté</h1>
              <p class="byline">Par A. Auteur</p>
              <div class="body">
                <div><p>Le conseil municipal a adopté le budget mardi soir.</p></div>
                <div><p>Les taxes foncières augmenteront de deux&nbsp;% cette année.</p></div>
                <h2>Réactions</h2>
                <blockquote><p>C'est un budget responsable, a dit la mairesse.</p></blockquote>
                <figure><p>Légende de la photo prise à l'hôtel de ville</p></figure>
                <div><p>L'opposition votera contre lors de la prochaine séance.</p></div>
                <button>Partager cet article</button>
                <h3>À lire aussi</h3>
              </div>
            </article></main>
            <aside><p>Les articles les plus lus de la semaine dernière sur notre site.</p></aside>
            <footer><p>Tous droits réservés, Le Journal inc., 2026, Montréal, Québec.</p></footer>
            </body></html>
            """;

    @Test
    void readsTheArticleBodyAndSkipsPageChrome() {
        RadioCanadaExtractor.Extracted ex = new GenericExtractor().extract(Jsoup.parse(HTML));

        assertThat(ex.title()).isEqualTo("Le budget est adopté");
        assertThat(ex.blocks()).extracting(RadioCanadaExtractor.Block::kind)
                .containsExactly("p", "p", "heading", "quote", "p");
        assertThat(ex.blocks()).extracting(RadioCanadaExtractor.Block::text).containsExactly(
                "Le conseil municipal a adopté le budget mardi soir.",
                "Les taxes foncières augmenteront de deux % cette année.",
                "Réactions",
                "C'est un budget responsable, a dit la mairesse.",
                "L'opposition votera contre lors de la prochaine séance.");
    }

    @Test
    void leavesTheCallersDocumentAlone() {
        Document doc = Jsoup.parse(HTML);
        new GenericExtractor().extract(doc);
        assertThat(doc.select("nav")).hasSize(1);
    }

    @Test
    void titleFallsBackToOpenGraphThenTheTitleTag() {
        assertThat(new GenericExtractor().extract(Jsoup.parse(
                "<head><meta property=og:title content='Titre OG'><title>T</title></head>")).title())
                .isEqualTo("Titre OG");
        assertThat(new GenericExtractor().extract(Jsoup.parse("<title> T </title>")).title()).isEqualTo("T");
    }

    @Test
    void pageWithoutParagraphsYieldsNoBlocks() {
        assertThat(new GenericExtractor().extract(Jsoup.parse("<h1>Hi</h1><p>court</p>")).blocks()).isEmpty();
    }
}
