package org.iphoto.controller;

import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Regression test for a bug where RootController's SPA-fallback forward() got @ResponseBody
// applied to it via the class-level @RestController annotation, so its "forward:/index.html"
// view-name directive was serialized as a literal response body string instead of being resolved
// into a real forward - reproduced live as a blank page (with that literal text) on any refresh
// of a non-root path. RootController is now split into @Controller (forward() resolves for real)
// with @ResponseBody only on the one JSON-returning method (getCurrentUser).
//
// Both paths used below require an authenticated session (SecurityConfig only permitAlls "/",
// "/share/**", "/album/**" singular, static assets, and index.html - not the plural app routes
// like "/albums/**"/"/spaces/**"), so log in first the same way every other MockMvc test here does.
class RootControllerTest extends AbstractIntegrationTest {

    @Test
    void nonApiPathForwardsToIndexHtmlInsteadOfReturningTheLiteralDirectiveString() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(get("/albums/some-id").session(session))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void nonApiTwoSegmentPathAlsoForwardsToIndexHtml() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(get("/spaces/some-space-id").session(session))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
