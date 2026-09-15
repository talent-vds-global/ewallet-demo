package com.ewallet.mobileapp.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Hai endpoint smoke test của BFF. {@code /trace-test} gọi hai tầng để chứng minh
 * collector thu được trace xuyên service — nó phải trả lời được cả khi tầng dưới chết.
 */
class BffControllerTest {

    private MockMvc mockMvcWith(RestClient restClient) {
        return MockMvcBuilders.standaloneSetup(new BffController(restClient)).build();
    }

    @Test
    @DisplayName("GET /api/ping báo service sống")
    void ping() throws Exception {
        mockMvcWith(mock(RestClient.class))
                .perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("ewallet-business-customer-mobileapp"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /api/trace-test trả nội dung mà tầng dưới phản hồi")
    void traceTestGoiDuocTangDuoi() throws Exception {
        RestClient restClient = mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().body(String.class))
                .thenReturn("{\"status\":\"UP\"}");

        mockMvcWith(restClient)
                .perform(get("/api/trace-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hop").value("mobileapp -> order"))
                .andExpect(jsonPath("$.downstream").value("{\"status\":\"UP\"}"));
    }

    @Test
    @DisplayName("tầng dưới chưa sẵn sàng thì vẫn trả 200 và báo unreachable")
    void tangDuoiChet() throws Exception {
        RestClient restClient = mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().body(String.class))
                .thenThrow(new RestClientException("connection refused"));

        mockMvcWith(restClient)
                .perform(get("/api/trace-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downstream").value("unreachable"));
    }
}
