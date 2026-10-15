package com.frenchreader.saved;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SavedControllerTest {

    private static final String BODY = """
            {"lemma":"faire partie de","pos":"idiom","meaning":"Etre un element de.","register":"courant",
             "gloss":"to be part of","note":"Followed by de.","word":"partie",
             "sentence":"Ils font partie de l'équipe.","articleUrl":"https://ici.radio-canada.ca/nouvelle/1/x"}""";

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new SavedController(new SavedStore((Path) null))).build();

    private ResultActions save(String json) throws Exception {
        return mvc.perform(post("/api/v1/saved").contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void saveListAndDeleteRoundTrip() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(
                save(BODY).andExpect(status().isCreated())
                        .andExpect(jsonPath("$.lemma").value("faire partie de"))
                        .andExpect(jsonPath("$.sentence").value("Ils font partie de l'équipe."))
                        .andExpect(jsonPath("$.savedAt").isNotEmpty())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");

        mvc.perform(get("/api/v1/saved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(delete("/api/v1/saved/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/saved")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/v1/saved/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void savingTheSameLemmaTwiceIs200WithTheSameId() throws Exception {
        String first = save(BODY).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = save(BODY).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
        mvc.perform(get("/api/v1/saved")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectsMissingLemmaMeaningOrOversizedFields() throws Exception {
        save("{\"meaning\":\"x\"}").andExpect(status().isBadRequest());
        save("{\"lemma\":\"  \",\"meaning\":\"x\"}").andExpect(status().isBadRequest());
        save("{\"lemma\":\"ainsi\"}").andExpect(status().isBadRequest());
        save("{\"lemma\":\"ainsi\",\"meaning\":\"" + "a".repeat(2001) + "\"}").andExpect(status().isBadRequest());
        save("not json").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/saved")).andExpect(jsonPath("$.length()").value(0));
    }
}
