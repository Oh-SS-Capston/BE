package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.config.OllamaConfig;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmOllamaClientSupportTest {

    private static final String GENERATE_URL = "http://localhost:11434/api/generate";

    private MockRestServiceServer server;

    private LlmOllamaClientSupport newSupport() {
        return newSupport(0);
    }

    private LlmOllamaClientSupport newSupport(int seed) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
        server = MockRestServiceServer.bindTo(builder).build();

        OllamaConfig config = new OllamaConfig();
        config.setModel("qwen3.5:9b");
        config.setNumCtx(16384);
        config.setNumPredict(6000);
        config.setSeed(seed);

        return new LlmOllamaClientSupport(builder.build(), config, new ObjectMapper());
    }

    @Test
    @DisplayName("/api/generate로 모델·stream·format·think를 담아 요청하고 response 필드를 파싱한다")
    void sendsGenerateRequestAndParsesResponse() {
        LlmOllamaClientSupport support = newSupport();

        server.expect(once(), requestTo(GENERATE_URL))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"model\":\"qwen3.5:9b\""),
                        org.hamcrest.Matchers.containsString("\"stream\":false"),
                        org.hamcrest.Matchers.containsString("\"format\":\"json\""),
                        org.hamcrest.Matchers.containsString("\"think\":false"),
                        org.hamcrest.Matchers.containsString("\"num_ctx\":16384")
                )))
                .andRespond(withSuccess("""
                        {"response":"{\\"cautions\\":[{\\"cautionId\\":\\"CAU-001\\"}]}","done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = support.call("step", "system", "user", 6000);

        assertThat(result.path("cautions").size()).isEqualTo(1);
        assertThat(result.path("cautions").get(0).path("cautionId").asText()).isEqualTo("CAU-001");
        server.verify();
    }

    @Test
    @DisplayName("시드가 0 이상이면 options에 담아 재현 가능한 생성을 요청한다")
    void sendsFixedSeedInOptions() {
        LlmOllamaClientSupport support = newSupport(20260818);

        server.expect(once(), requestTo(GENERATE_URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"seed\":20260818")))
                .andRespond(withSuccess("""
                        {"response":"{\\"cautions\\":[]}","done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));

        support.call("step", "system", "user", 6000);

        server.verify();
    }

    @Test
    @DisplayName("시드가 음수면 options에서 생략해 Ollama 기본 임의 시드를 쓴다")
    void omitsSeedWhenNegative() {
        LlmOllamaClientSupport support = newSupport(-1);

        server.expect(once(), requestTo(GENERATE_URL))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"seed\"")
                )))
                .andRespond(withSuccess("""
                        {"response":"{\\"cautions\\":[]}","done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));

        support.call("step", "system", "user", 6000);

        server.verify();
    }

    @Test
    @DisplayName("num_predict 상한에 걸려 잘린 JSON은 균형을 복원해 파싱한다")
    void recoversTruncatedResponse() {
        LlmOllamaClientSupport support = newSupport();

        server.expect(once(), requestTo(GENERATE_URL))
                .andRespond(withSuccess("""
                        {"response":"{\\"cautions\\":[{\\"cautionId\\":\\"CAU-001\\",\\"title\\":\\"미완성","done_reason":"length"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = support.call("step", "system", "user", 6000);

        assertThat(result.path("cautions").get(0).path("cautionId").asText()).isEqualTo("CAU-001");
        server.verify();
    }

    @Test
    @DisplayName("추론 블록이 섞여 나와도 제거 후 파싱한다")
    void stripsThinkBlockBeforeParsing() {
        LlmOllamaClientSupport support = newSupport();

        server.expect(once(), requestTo(GENERATE_URL))
                .andRespond(withSuccess("""
                        {"response":"<think>어떤 주의사항을 쓸지 고민</think>\\n{\\"cautions\\":[]}","done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = support.call("step", "system", "user", 6000);

        assertThat(result.path("cautions").isArray()).isTrue();
        assertThat(result.path("cautions")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("모델을 찾지 못한 404는 재시도하지 않고 즉시 실패한다")
    void doesNotRetryOnModelNotFound() {
        LlmOllamaClientSupport support = newSupport();

        server.expect(once(), requestTo(GENERATE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"error\":\"model 'qwen3.5:9b' not found\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> support.call("step", "system", "user", 6000))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).getCode())
                .isEqualTo(LlmErrorCode.CLAUDE_API_CALL_FAILED);

        // once()로 등록한 기대가 정확히 1회만 소비됐다 = 재시도가 없었다.
        server.verify();
    }

    @Test
    @DisplayName("응답이 JSON이 아니면 상위 compact 재시도로 넘기도록 파싱 실패를 던진다")
    void throwsParseFailedOnNonJsonResponse() {
        LlmOllamaClientSupport support = newSupport();

        server.expect(once(), requestTo(GENERATE_URL))
                .andRespond(withSuccess("""
                        {"response":"죄송합니다. 요청을 이해하지 못했습니다.","done_reason":"stop"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> support.call("step", "system", "user", 6000))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).getCode())
                .isEqualTo(LlmErrorCode.RESPONSE_PARSE_FAILED);

        server.verify();
    }
}
