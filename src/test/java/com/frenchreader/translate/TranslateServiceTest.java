package com.frenchreader.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frenchreader.cache.FileBackedCache;
import com.sun.net.httpserver.HttpServer;

/** Real SDK against a fake Messages API on localhost, as in ClaudeLookupServiceTest. */
class TranslateServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PARAGRAPH = "Le gouvernement a annoncé la mesure mardi. Elle entrera en vigueur en 2027.";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody;

    @BeforeEach
    void startFakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        reply("The government announced the measure on Tuesday. It will take effect in 2027.", "end_turn");
    }

    @AfterEach
    void stopFakeApi() {
        server.stop(0);
    }

    private void reply(String text, String stopReason) {
        status = 200;
        responseBody = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"text\",\"text\":"
                + JSON.valueToTree(text) + "}],\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
    }

    private TranslateService service() {
        return new TranslateService(AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build(), new FileBackedCache<>(null, 10, TranslateResponse.class));
    }

    @Test
    void sendsTheParagraphAndReturnsTheTranslation() throws Exception {
        assertThat(service().translate(PARAGRAPH)).startsWith("The government announced");

        JsonNode body = JSON.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("messages").path(0).path("content").toString())
                .contains("<paragraph>Le gouvernement a annoncé");
        assertThat(body.path("system").toString()).contains("English");
    }

    @Test
    void sameParagraphIsTranslatedOnceThenServedFromCache() {
        TranslateService s = service();
        String first = s.translate(PARAGRAPH);
        String again = s.translate("  " + PARAGRAPH + "\n");

        assertThat(again).isEqualTo(first);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cutOffTranslationIs502AndNotCached() {
        reply("The government announced", "max_tokens");
        TranslateService s = service();
        assertThatThrownBy(() -> s.translate(PARAGRAPH))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        reply("The government announced the measure.", "end_turn");
        assertThat(s.translate(PARAGRAPH)).isEqualTo("The government announced the measure.");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void upstreamErrorIs502() {
        status = 500;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"nope\"}}";
        assertThatThrownBy(() -> service().translate(PARAGRAPH))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void missingApiKeyIs503() {
        TranslateService s = new TranslateService(" ", new FileBackedCache<>(null, 10, TranslateResponse.class));
        assertThatThrownBy(() -> s.translate(PARAGRAPH))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).contains("CLAUDE_API_ONLY_KEY");
                });
    }
}
