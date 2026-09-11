package com.flowdesk.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.bo.AuthLoginBO;
import com.flowdesk.auth.domain.bo.AuthLoginResultBO;
import com.flowdesk.auth.domain.bo.AuthUserBO;
import com.flowdesk.auth.domain.vo.AuthVO;
import com.flowdesk.auth.service.AuthLoginService;
import com.flowdesk.shared.exception.ApiErrorWriter;
import com.flowdesk.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private final AuthLoginService authLoginService = mock(AuthLoginService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        AuthProperties authProperties = new AuthProperties();
        authProperties.setRefreshExpiration(Duration.ofDays(7));
        authProperties.setCookieSecure(true);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authLoginService, authProperties))
                .setControllerAdvice(new GlobalExceptionHandler(new ApiErrorWriter(objectMapper)))
                .build();
    }

    @Test
    void loginReturnsDocumentedEnvelope() throws Exception {
        AuthUserBO user = new AuthUserBO(
                42L,
                "alice",
                "Alice",
                List.of("EMPLOYEE"),
                List.of("TICKET_CREATE")
        );
        when(authLoginService.login(any(AuthVO.class)))
                .thenReturn(new AuthLoginResultBO(
                        new AuthLoginBO("signed-token", 900, user),
                        "raw-refresh-token"));

        mockMvc.perform(post("/fd/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.accessToken").value("signed-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.user.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("FLOWDESK_REFRESH=raw-refresh-token"),
                        org.hamcrest.Matchers.containsString("Path=/fd/v1/auth"),
                        org.hamcrest.Matchers.containsString("Max-Age=604800"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict")
                )));
    }

    @Test
    void loginRejectsBlankCredentialsBeforeCallingService() throws Exception {
        mockMvc.perform(post("/fd/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
