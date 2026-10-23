package com.example.bankigservicescientific;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The single page is served by the same application at the root URL. */
class StaticPageTests extends AbstractIntegrationTest {

    @Test
    void rootIsTheWelcomePage() throws Exception {
        // MockMvc does not execute forwards; the real GET / returning HTML is checked against the running server.
        mvc.perform(get("/")).andExpect(forwardedUrl("index.html"));
    }

    @Test
    void indexPageIsServedAsHtml() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("app.js")))
                .andExpect(content().string(containsString("styles.css")));
    }

    @Test
    void scriptAndStylesAreServed() throws Exception {
        mvc.perform(get("/app.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Idempotency-Key")));
        mvc.perform(get("/styles.css")).andExpect(status().isOk());
    }
}
