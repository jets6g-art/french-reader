package com.frenchreader.translate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranslateControllerTest {

    private final TranslateService service = mock(TranslateService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TranslateController(service)).build();

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/v1/translate").contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void returnsTheTranslation() throws Exception {
        when(service.translate(anyString())).thenReturn("It will take effect in 2027.");
        postJson("{\"paragraph\":\"Elle entrera en vigueur en 2027.\",\"articleUrl\":\"https://ici.radio-canada.ca/nouvelle/1/x\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translation").value("It will take effect in 2027."));
    }

    @Test
    void rejectsMissingBlankOrOversizedParagraph() throws Exception {
        postJson("{}").andExpect(status().isBadRequest());
        postJson("{\"paragraph\":\"  \"}").andExpect(status().isBadRequest());
        postJson("{\"paragraph\":\"" + "a".repeat(5001) + "\"}").andExpect(status().isBadRequest());
        postJson("not json").andExpect(status().isBadRequest());
    }
}
