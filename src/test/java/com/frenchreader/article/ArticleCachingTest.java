package com.frenchreader.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.frenchreader.cache.FileBackedCache;

class ArticleCachingTest {

    private static final String URL = "https://ici.radio-canada.ca/nouvelle/1/x";

    private static Document page(String title) {
        return Jsoup.parse("<h1>" + title + "</h1><div class=\"flow-root\"><div><p>Bonjour le monde.</p></div></div>");
    }

    private final ArticleFetcher fetcher = mock(ArticleFetcher.class);

    private ArticleService service(Duration ttl) {
        return new ArticleService(fetcher, new FileBackedCache<>(null, 10, Article.class), ttl);
    }

    @Test
    void freshCopyIsServedWithoutTouchingTheSource() {
        when(fetcher.fetch(any(URI.class))).thenReturn(page("T"));
        ArticleService service = service(Duration.ofHours(1));

        Article first = service.load(URL);
        Article second = service.load(URL);

        verify(fetcher, times(1)).fetch(any(URI.class));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void sameArticleTypedDifferentlyIsOneCacheEntry() {
        when(fetcher.fetch(any(URI.class))).thenReturn(page("T"));
        ArticleService service = service(Duration.ofHours(1));

        service.load(URL);
        service.load("https://ICI.radio-canada.ca/nouvelle/1/x#comments");

        verify(fetcher, times(1)).fetch(any(URI.class));
    }

    @Test
    void expiredCopyIsRefetchedAndReplaced() {
        when(fetcher.fetch(any(URI.class))).thenReturn(page("Old")).thenReturn(page("New"));
        ArticleService service = service(Duration.ZERO);

        assertThat(service.load(URL).title()).isEqualTo("Old");
        assertThat(service.load(URL).title()).isEqualTo("New");
        verify(fetcher, times(2)).fetch(any(URI.class));
    }

    @Test
    void expiredCopyIsStillServedWhenTheSourceIsDown() {
        when(fetcher.fetch(any(URI.class))).thenReturn(page("Saved"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "down"));
        ArticleService service = service(Duration.ZERO);

        service.load(URL);
        assertThat(service.load(URL).title()).isEqualTo("Saved");
    }

    @Test
    void sourceFailureWithNothingCachedStillFails() {
        when(fetcher.fetch(any(URI.class))).thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "slow"));
        assertThatThrownBy(() -> service(Duration.ofHours(1)).load(URL))
                .hasMessageContaining("504");
    }

    @Test
    void pagesWithNoArticleAreNotCached() {
        when(fetcher.fetch(any(URI.class))).thenReturn(Jsoup.parse("<h1>x</h1>")).thenReturn(page("Good"));
        ArticleService service = service(Duration.ofHours(1));

        assertThatThrownBy(() -> service.load(URL)).hasMessageContaining("422");
        assertThat(service.load(URL).title()).isEqualTo("Good");
        verify(fetcher, times(2)).fetch(any(URI.class));
    }

    @Test
    void rejectedUrlsNeverReachTheCacheOrTheSource() {
        assertThatThrownBy(() -> service(Duration.ofHours(1)).load("ftp://example.com/x"))
                .hasMessageContaining("400");
        verify(fetcher, times(0)).fetch(any(URI.class));
    }
}
