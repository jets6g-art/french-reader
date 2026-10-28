package com.frenchreader.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArticleControllerTest {

    private static com.frenchreader.cache.FileBackedCache<Article> memoryCache() {
        return new com.frenchreader.cache.FileBackedCache<>(null, 10, Article.class);
    }

    private static final String URL = "https://ici.radio-canada.ca/nouvelle/1/x";

    private static MockMvc mvcFor(String html) {
        ArticleFetcher fetcher = mock(ArticleFetcher.class);
        when(fetcher.fetch(URI.create(URL))).thenReturn(Jsoup.parse(html));
        return MockMvcBuilders.standaloneSetup(new ArticleController(new ArticleService(fetcher, memoryCache(), java.time.Duration.ofHours(1)))).build();
    }

    @Test
    void returnsTokenizedArticle() throws Exception {
        MockMvc mvc = mvcFor("""
                <h1>Titre de l'année</h1>
                <div class="flow-root"><div>
                  <p>L'entreprise a gagné. Tant mieux!</p>
                  <h2>Suite</h2>
                  <p>Fin.</p>
                </div></div>""");

        mvc.perform(get("/api/v1/article").param("url", URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Titre de l'année"))
                .andExpect(jsonPath("$.titleTokens[0].text").value("Titre"))
                .andExpect(jsonPath("$.titleTokens[2].text").value("l'"))
                .andExpect(jsonPath("$.titleTokens[3].text").value("année"))
                .andExpect(jsonPath("$.sentences[0].tokens[0].idx").value(4))
                .andExpect(jsonPath("$.source").value("Radio-Canada"))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.sentences.length()").value(4))
                .andExpect(jsonPath("$.sentences[0].id").value(0))
                .andExpect(jsonPath("$.sentences[0].kind").value("p"))
                .andExpect(jsonPath("$.sentences[0].tokens[0].text").value("L'"))
                .andExpect(jsonPath("$.sentences[0].tokens[0].ws").value(""))
                .andExpect(jsonPath("$.sentences[0].tokens[1].idx").value(5))
                .andExpect(jsonPath("$.sentences[1].paragraph").value(0))
                .andExpect(jsonPath("$.sentences[2].paragraph").value(1))
                .andExpect(jsonPath("$.sentences[2].kind").value("heading"));
    }

    @Test
    void otherSitesUseGenericExtractionAndTheirOwnName() throws Exception {
        String other = "http://www.example.fr/actualites/42";
        ArticleFetcher fetcher = mock(ArticleFetcher.class);
        when(fetcher.fetch(URI.create(other))).thenReturn(Jsoup.parse("""
                <head><meta property="og:site_name" content="Le Journal"></head>
                <nav><p>Accueil, Politique, Économie, Culture, Sports et Météo</p></nav>
                <h1>Un titre</h1>
                <div class="corps"><p>Le conseil municipal a adopté le budget mardi soir.</p>
                  <p>Les taxes foncières augmenteront de deux pour cent cette année.</p></div>"""));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArticleController(
                new ArticleService(fetcher, memoryCache(), java.time.Duration.ofHours(1)))).build();

        mvc.perform(get("/api/v1/article").param("url", other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Un titre"))
                .andExpect(jsonPath("$.source").value("Le Journal"))
                .andExpect(jsonPath("$.sentences.length()").value(2))
                .andExpect(jsonPath("$.sentences[0].tokens[0].text").value("Le"));
    }

    @Test
    void sourceNameFallsBackToTheHost() {
        assertThat(ArticleService.sourceName(URI.create("https://www.lapresse.ca/x"), Jsoup.parse("")))
                .isEqualTo("lapresse.ca");
    }

    @Test
    void cacheKeyIgnoresCaseDefaultPortAndFragment() {
        assertThat(ArticleService.cacheKey(URI.create("HTTPS://Ici.Radio-Canada.ca:443/nouvelle/1/x#c")))
                .isEqualTo("https://ici.radio-canada.ca/nouvelle/1/x");
        assertThat(ArticleService.cacheKey(URI.create("http://example.fr:8081/a?b=1")))
                .isEqualTo("http://example.fr:8081/a?b=1");
    }

    @Test
    void malformedUrlIsRejectedBeforeAnyFetch() {
        ArticleService service = new ArticleService(mock(ArticleFetcher.class), memoryCache(), java.time.Duration.ofHours(1));
        for (String bad : new String[] {
                "ftp://example.fr/x",
                "file:///C:/Windows/win.ini",
                "javascript:alert(1)",
                "https://user:pw@example.fr/x",
                "https:///nohost",
                "not a url", ""}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.load(bad))
                    .as(bad)
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("400");
        }
    }

    @Test
    void pageWithNoBodyIs422() {
        ArticleService service;
        ArticleFetcher fetcher = mock(ArticleFetcher.class);
        when(fetcher.fetch(URI.create(URL))).thenReturn(Jsoup.parse("<h1>x</h1>"));
        service = new ArticleService(fetcher, memoryCache(), java.time.Duration.ofHours(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.load(URL))
                .hasMessageContaining("422");
    }
}
