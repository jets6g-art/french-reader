package com.frenchreader.lookup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class LookupController {

    // Bounds keep a bad client from pushing arbitrary amounts of text into the (paid) LLM call later.
    static final int MAX_WORD_CHARS = 200;
    static final int MAX_SENTENCE_CHARS = 2000;

    private final LookupService lookups;

    LookupController(LookupService lookups) {
        this.lookups = lookups;
    }

    @PostMapping("/lookup")
    public LookupResponse lookup(@RequestBody LookupRequest request) {
        require(request.word(), "word", MAX_WORD_CHARS);
        require(request.sentence(), "sentence", MAX_SENTENCE_CHARS);
        return lookups.lookup(request);
    }

    private static void require(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        if (value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " exceeds " + max + " characters");
        }
    }
}
