package com.frenchreader.lookup;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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

    private static final Logger log = LoggerFactory.getLogger(LookupController.class);
    private static final long STREAM_TIMEOUT_MS = 15_000;

    private final LookupService lookups;
    private final Executor streamer = Executors.newCachedThreadPool();

    LookupController(LookupService lookups) {
        this.lookups = lookups;
    }

    @PostMapping("/lookup")
    public LookupResponse lookup(@RequestBody LookupRequest request) {
        require(request.word(), "word", MAX_WORD_CHARS);
        require(request.sentence(), "sentence", MAX_SENTENCE_CHARS);
        return lookups.lookup(request);
    }

    /**
     * Same lookup as above, as server-sent events: one {@code field} event per completed field
     * ({@code {"name","value"}}), then {@code done} with the full LookupResponse, or {@code error}
     * with {@code {"status","message"}}. Validation failures are still plain HTTP 400s.
     */
    @PostMapping(path = "/lookup/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter lookupStream(@RequestBody LookupRequest request) {
        require(request.word(), "word", MAX_WORD_CHARS);
        require(request.sentence(), "sentence", MAX_SENTENCE_CHARS);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamer.execute(() -> {
            try {
                LookupResponse result = lookups.lookup(request,
                        (name, value) -> send(emitter, "field", new FieldEvent(name, value)));
                send(emitter, "done", result);
            } catch (ResponseStatusException e) {
                send(emitter, "error", Map.of("status", e.getStatusCode().value(),
                        "message", e.getReason() == null ? "Lookup failed" : e.getReason()));
            } catch (RuntimeException e) {
                log.error("Streaming lookup failed: {}", e.getClass().getSimpleName()); // never the sentence
                send(emitter, "error", Map.of("status", 500, "message", "Lookup failed"));
            }
            emitter.complete();
        });
        return emitter;
    }

    record FieldEvent(String name, String value) {
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException clientGone) {
            // browser closed the connection (newer click, popup closed): nothing left to tell
        }
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
