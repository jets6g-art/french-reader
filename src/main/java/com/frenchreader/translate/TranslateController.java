package com.frenchreader.translate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class TranslateController {

    // A long Radio-Canada paragraph is under 2000 characters; the bound keeps the paid call small.
    static final int MAX_PARAGRAPH_CHARS = 5000;

    private final TranslateService translations;

    TranslateController(TranslateService translations) {
        this.translations = translations;
    }

    /** POST, like lookup: the paragraph is too long for a query string and shouldn't land in access logs. */
    @PostMapping("/translate")
    public TranslateResponse translate(@RequestBody TranslateRequest request) {
        String paragraph = request.paragraph();
        if (paragraph == null || paragraph.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paragraph is required");
        }
        if (paragraph.length() > MAX_PARAGRAPH_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "paragraph exceeds " + MAX_PARAGRAPH_CHARS + " characters");
        }
        return new TranslateResponse(translations.translate(paragraph));
    }
}
