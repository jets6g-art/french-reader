package com.frenchreader.article;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ArticleController {

    // A long feature article is around 40,000 characters.
    static final int MAX_TEXT_LENGTH = 100_000;

    private final ArticleService articles;

    ArticleController(ArticleService articles) {
        this.articles = articles;
    }

    @GetMapping("/article")
    public Article article(@RequestParam String url) {
        return articles.load(url);
    }

    record PastedText(String title, String text) {
    }

    /** POST, like lookup: the text is too long for a query string and shouldn't land in access logs. */
    @PostMapping("/article/text")
    public Article text(@RequestBody PastedText body) {
        if (body.text() == null || body.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The pasted text is empty");
        }
        if (body.text().length() > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The pasted text is too long (over " + MAX_TEXT_LENGTH + " characters)");
        }
        return articles.fromText(body.title(), body.text());
    }
}
