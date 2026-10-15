package com.frenchreader.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Runs the real Anthropic SDK against a fake Messages API on localhost, so request building,
 * structured-output parsing and error mapping are exercised without an API key or network.
 */
class ClaudeLookupServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LookupRequest REQUEST = new LookupRequest("toutefois",
            "Toutefois, il n'a pas répondu.", "https://ici.radio-canada.ca/nouvelle/1/x");

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody;
    private volatile long delayMs = 0;

    @BeforeEach
    void startFakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                // client gave up
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
            } catch (IOException clientGone) {
                // timeout test: the client already disconnected
            } finally {
                exchange.close();
            }
        });
        server.start();
        replyWithText("{\"lemma\":\"toutefois\",\"pos\":\"adverb\","
                + "\"meaning\":\"Introduit une restriction.\",\"register\":\"soutenu\","
                + "\"gloss\":\"however\",\"note\":\"Usually opens the sentence, followed by a comma.\"}");
    }

    @AfterEach
    void stopFakeApi() {
        server.stop(0);
    }

    private void replyWithText(String text) {
        status = 200;
        responseBody = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"text\",\"text\":"
                + JSON.valueToTree(text) + "}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
    }

    private void replyWithError(int httpStatus, String type) {
        status = httpStatus;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"nope\"}}";
    }

    private ClaudeLookupService service(Duration timeout) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .timeout(timeout)
                .maxRetries(0)
                .build();
        return new ClaudeLookupService(client);
    }

    private ResponseStatusException failure() {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                () -> service(Duration.ofSeconds(5)).lookup(REQUEST));
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        return (ResponseStatusException) t;
    }

    @Test
    void parsesStructuredReplyIntoAllSixFields() {
        LookupResponse r = service(Duration.ofSeconds(5)).lookup(REQUEST);

        assertThat(r.lemma()).isEqualTo("toutefois");
        assertThat(r.pos()).isEqualTo("adverb");
        assertThat(r.meaning()).isEqualTo("Introduit une restriction.");
        assertThat(r.register()).isEqualTo("soutenu");
        assertThat(r.gloss()).isEqualTo("however");
        assertThat(r.note()).startsWith("Usually opens");
    }

    @Test
    void sendsASingleShotHaikuRequestWithSchemaAndContext() throws Exception {
        service(Duration.ofSeconds(5)).lookup(REQUEST);

        assertThat(lastApiKey.get()).isEqualTo("test-key");
        JsonNode body = JSON.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(400);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("temperature")).isFalse(); // sampling params are rejected by current models
        assertThat(body.path("messages")).hasSize(1);
        String user = body.path("messages").path(0).path("content").toString();
        assertThat(user).contains("<word>toutefois</word>").contains("n'a pas répondu");
        assertThat(body.path("system").toString()).contains("idiom");
        // structured output: the schema must carry all six fields
        JsonNode schema = body.path("output_config").path("format").path("schema").path("properties");
        assertThat(schema.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("lemma", "pos", "meaning", "register", "gloss", "note");
    }

    @Test
    void missingApiKeyIs503WithAnActionableMessage() {
        assertThatThrownBy(() -> new ClaudeLookupService("  ").lookup(REQUEST))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).contains("ANTHROPIC_API_KEY");
                });
    }

    @Test
    void rejectedKeyIs502() {
        replyWithError(401, "authentication_error");
        ResponseStatusException e = failure();
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(e.getReason()).contains("API key");
    }

    @Test
    void rateLimitIs503() {
        replyWithError(429, "rate_limit_error");
        assertThat(failure().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void upstreamServerErrorIs502() {
        replyWithError(500, "api_error");
        ResponseStatusException e = failure();
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(e.getReason()).contains("500");
    }

    @Test
    void slowReplyTimesOutAs504() {
        delayMs = 3_000;
        long start = System.nanoTime();
        assertThatThrownBy(() -> service(Duration.ofMillis(500)).lookup(REQUEST))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void replyThatIsNotValidJsonIs502() {
        replyWithText("Sorry, I can't help with that.");
        assertThat(failure().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void emptyExplanationIs502() {
        replyWithText("{\"lemma\":\"x\",\"pos\":\"noun\",\"meaning\":\"\",\"register\":\"courant\","
                + "\"gloss\":\"x\",\"note\":\"x\"}");
        assertThat(failure().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
