package com.frenchreader.article;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class ArticleFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Safari/537.36";
    private static final int TIMEOUT_MS = 5_000;
    private static final int MAX_REDIRECTS = 3;

    Document fetch(URI uri) {
        URI current = uri;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                // Redirects are followed by hand so every hop is re-checked against SourcePolicy.
                Connection.Response res = Jsoup.connect(current.toString())
                        .userAgent(USER_AGENT)
                        .header("Accept-Language", "fr-CA,fr;q=0.9")
                        .timeout(TIMEOUT_MS)
                        .followRedirects(false)
                        .ignoreHttpErrors(true)
                        .execute();
                int status = res.statusCode();
                if (status >= 300 && status < 400 && res.hasHeader("Location")) {
                    URI next = current.resolve(res.header("Location"));
                    current = SourcePolicy.check(next.toString()).orElseThrow(() ->
                            new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                    "Source redirected to an unsupported location"));
                    continue;
                }
                if (status == 404) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found at source");
                }
                if (status != 200) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source returned HTTP " + status);
                }
                return res.parse();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Too many redirects from source");
        } catch (SocketTimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Source timed out after 5s");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not fetch article: " + e.getMessage());
        }
    }
}
