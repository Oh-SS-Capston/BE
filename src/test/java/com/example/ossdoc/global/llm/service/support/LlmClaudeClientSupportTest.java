package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.config.LlmConfig;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmClaudeClientSupportTest {

    @Test
    @DisplayName("primary 응답 파싱 실패는 Haiku fallback 없이 상위 compact 재시도로 전달한다")
    void parseFailureDoesNotFallbackToHaiku() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.anthropic.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        LlmConfig config = new LlmConfig();
        config.setModel("claude-sonnet-4-6");
        config.setHaikuModel("claude-haiku-4-5");
        config.setHaikuFallbackEnabled(true);

        LlmClaudeClientSupport support = new LlmClaudeClientSupport(
                restClient,
                config,
                new ObjectMapper()
        );

        server.expect(once(), requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(content().string(containsString("\"model\":\"claude-sonnet-4-6\"")))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"not-json"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> support.callClaudeWithHaikuFallback("step", "system", "user", 1000))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).getCode())
                .isEqualTo(LlmErrorCode.RESPONSE_PARSE_FAILED);

        server.verify();
    }
}
