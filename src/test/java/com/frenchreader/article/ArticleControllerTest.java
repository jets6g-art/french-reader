package com.frenchreader.article;

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

    private static final String URL = "https://ici.radio-canada.ca/nouvelle/1/x";

    private static MockMvc mvcFor(String html) {
        ArticleFetcher fetcher = mock(ArticleFetcher.class);
        when(fetcher.fetch(URI.create(URL))).thenReturn(Jsoup.parse(html));
        return MockMvcBuilders.standaloneSetup(new ArticleController(new ArticleService(fetcher))).build();
    }

    @Test
    void returnsTokenizedArticle() throws Exception {
        MockMvc mvc = mvcFor("""
                <h1>Titre</h1>
                <div class="flow-root"><div>
                  <p>L'entreprise a gagné. Tant mieux!</p>
                  <h2>Suite</h2>
                  <p>Fin.</p>
                </div></div>""");

        mvc.perform(get("/api/v1/article").param("url", URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Titre"))
                .andExpect(jsonPath("$.source").value("Radio-Canada"))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.sentences.length()").value(4))
                .andExpect(jsonPath("$.sentences[0].id").value(0))
                .andExpect(jsonPath("$.sentences[0].kind").value("p"))
                .andExpect(jsonPath("$.sentences[0].tokens[0].text").value("L'"))
                .andExpect(jsonPath("$.sentences[0].tokens[0].ws").value(""))
                .andExpect(jsonPath("$.sentences[0].tokens[1].idx").value(1))
                .andExpect(jsonPath("$.sentences[1].paragraph").value(0))
                .andExpect(jsonPath("$.sentences[2].paragraph").value(1))
                .andExpect(jsonPath("$.sentences[2].kind").value("heading"));
    }

    @Test
    void unsupportedHostIsRejectedBeforeAnyFetch() {
        ArticleService service = new ArticleService(mock(ArticleFetcher.class));
        for (String bad : new String[] {
                "http://ici.radio-canada.ca/nouvelle/1/x",
                "https://example.com/nouvelle/1/x",
                "https://ici.radio-canada.ca@evil.example/x",
                "https://ici.radio-canada.ca.evil.example/x",
                "https://localhost:8080/",
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
        service = new ArticleService(fetcher);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.load(URL))
                .hasMessageContaining("422");
    }
}
