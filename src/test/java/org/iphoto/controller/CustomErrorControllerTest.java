package org.iphoto.controller;

import jakarta.servlet.RequestDispatcher;
import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomErrorControllerTest extends AbstractIntegrationTest {

    @Test
    void errorEndpointWith401AndPageUriRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 401)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/albums"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void errorEndpointWith401AndApiUriReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 401)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/albums"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void errorEndpointWithOtherStatusForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/some-page"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
