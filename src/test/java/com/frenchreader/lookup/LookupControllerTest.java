package com.frenchreader.lookup;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LookupControllerTest {

    private static final LookupService STUB =
            req -> new LookupResponse(req.word(), "pos", "meaning", "register", "gloss", "note", "", "", "", "");

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new LookupController(STUB)).build();

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/v1/lookup").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void returnsAllSixFields() throws Exception {
        postJson("""
                {"word":"toutefois","sentence":"Toutefois, il est parti.","articleUrl":"https://ici.radio-canada.ca/nouvelle/1/x"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lemma").value("toutefois"))
                .andExpect(jsonPath("$.pos").isNotEmpty())
                .andExpect(jsonPath("$.meaning").isNotEmpty())
                .andExpect(jsonPath("$.register").isNotEmpty())
                .andExpect(jsonPath("$.gloss").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    private String stream(LookupService service, String json) throws Exception {
        MockMvc m = MockMvcBuilders.standaloneSetup(new LookupController(service)).build();
        var started = m.perform(post("/api/v1/lookup/stream").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted()).andReturn();
        return m.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }

    private static final String BODY = "{\"word\":\"toutefois\",\"sentence\":\"Toutefois, il est parti.\"}";

    @Test
    void streamSendsFieldEventsThenDone() throws Exception {
        LookupService streaming = new LookupService() {
            @Override
            public LookupResponse lookup(LookupRequest req) {
                return STUB.lookup(req);
            }

            @Override
            public LookupResponse lookup(LookupRequest req, java.util.function.BiConsumer<String, String> onField) {
                onField.accept("lemma", req.word());
                onField.accept("pos", "adverb");
                return lookup(req);
            }
        };
        String out = stream(streaming, BODY);

        org.assertj.core.api.Assertions.assertThat(out)
                .contains("event:field", "{\"name\":\"lemma\",\"value\":\"toutefois\"}", "{\"name\":\"pos\",\"value\":\"adverb\"}")
                .contains("event:done", "\"gloss\":\"gloss\"");
        org.assertj.core.api.Assertions.assertThat(out.indexOf("event:field")).isLessThan(out.indexOf("event:done"));
    }

    @Test
    void streamReportsFailuresAsAnErrorEvent() throws Exception {
        String out = stream(req -> {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "Claude did not answer within 10s");
        }, BODY);

        org.assertj.core.api.Assertions.assertThat(out)
                .contains("event:error", "\"status\":504", "Claude did not answer within 10s");
    }

    @Test
    void streamRejectsBadInputWithPlain400() throws Exception {
        mvc.perform(post("/api/v1/lookup/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"word\":\"\",\"sentence\":\"x\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void articleUrlIsOptional() throws Exception {
        postJson("{\"word\":\"ainsi\",\"sentence\":\"Ainsi va la vie.\"}").andExpect(status().isOk());
    }

    @Test
    void rejectsMissingBlankOrOversizedInput() throws Exception {
        postJson("{\"sentence\":\"Une phrase.\"}").andExpect(status().isBadRequest());
        postJson("{\"word\":\"  \",\"sentence\":\"Une phrase.\"}").andExpect(status().isBadRequest());
        postJson("{\"word\":\"mot\"}").andExpect(status().isBadRequest());
        postJson("{\"word\":\"" + "a".repeat(201) + "\",\"sentence\":\"Une phrase.\"}").andExpect(status().isBadRequest());
        postJson("{\"word\":\"mot\",\"sentence\":\"" + "a".repeat(2001) + "\"}").andExpect(status().isBadRequest());
        postJson("not json").andExpect(status().isBadRequest());
    }
}
