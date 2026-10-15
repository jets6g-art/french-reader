package com.frenchreader.cache;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.frenchreader.article.Article;
import com.frenchreader.lookup.LookupResponse;

@Configuration
class CacheConfig {

    @Bean
    FileBackedCache<Article> articleCache(@Value("${reader.cache.dir:data/cache}") String dir,
                                          @Value("${reader.cache.max-articles:100}") int max) {
        return new FileBackedCache<>(Path.of(dir, "articles.json"), max, Article.class);
    }

    @Bean
    FileBackedCache<LookupResponse> lookupCache(@Value("${reader.cache.dir:data/cache}") String dir,
                                                @Value("${reader.cache.max-lookups:5000}") int max) {
        return new FileBackedCache<>(Path.of(dir, "lookups.json"), max, LookupResponse.class);
    }
}
