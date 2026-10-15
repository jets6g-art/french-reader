package com.frenchreader.lookup;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LookupControllerTest {

    private static final LookupService STUB =
            req -> new LookupResponse(req.word(), "pos", "meaning", "register", "gloss", "note");

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
