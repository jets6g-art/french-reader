package com.frenchreader.article;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ArticleController {

    private final ArticleService articles;

    ArticleController(ArticleService articles) {
        this.articles = articles;
    }

    @GetMapping("/article")
    public Article article(@RequestParam String url) {
        return articles.load(url);
    }
}
