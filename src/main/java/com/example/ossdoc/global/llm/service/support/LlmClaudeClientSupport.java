package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.config.LlmConfig;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Anthropic Claude API 호출/재시도/응답 파싱 전담 컴포넌트.
 *
 * <p>{@code ossdoc.llm.provider=claude}일 때만 빈으로 등록된다.
 * 기본값은 ollama이므로 이 구현은 명시적으로 되돌렸을 때만 동작한다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ossdoc.llm.provider", havingValue = "claude")
public class LlmClaudeClientSupport implements LlmChatClient {

    private static final int MAX_CLAUDE_RETRY_ATTEMPTS = 2;

    private final RestClient claudeRestClient;
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper;

    /**
     * ollamaRestClient 빈이 함께 존재하므로 타입만으로는 주입 대상이 모호하다.
     * lombok.config가 없어 @Qualifier가 생성자로 복사되지 않아 생성자를 직접 선언한다.
     */
    public LlmClaudeClientSupport(
            @Qualifier("claudeRestClient") RestClient claudeRestClient,
            LlmConfig llmConfig,
            ObjectMapper objectMapper
    ) {
        this.claudeRestClient = claudeRestClient;
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 실행 시 사용할 1순위 모델을 결정하는 역할.
     */
    @Override
    public String resolvePrimaryModel() {
        String model = llmConfig.getModel();
        if (model == null || model.isBlank()) {
            return llmConfig.getHaikuModel();
        }
        return model.trim();
    }

    /**
     * Claude 호출을 수행하고 필요 시 fallback 모델로 재시도하는 역할.
     */
    @Override
    public JsonNode call(
            String stepName,
            String systemPrompt,
            String userMessage,
            int maxTokens
    ) {
        String primaryModel = resolvePrimaryModel();
        String fallbackModel = resolveFallbackModel();
        int effectiveMaxTokens = Math.max(1, Math.min(maxTokens, llmConfig.getMaxTokens()));
        // 실제 적용된 토큰 상한을 남겨 설정 불일치를 빠르게 확인한다.
        log.info(
                "[LlmClaudeClientSupport] {} token config. requestedMaxTokens={}, globalMaxTokens={}, effectiveMaxTokens={}, primaryModel={}",
                stepName,
                maxTokens,
                llmConfig.getMaxTokens(),
                effectiveMaxTokens,
                primaryModel
        );
        try {
            return callClaudeWithModel(systemPrompt, userMessage, maxTokens, primaryModel);
        } catch (LlmException firstFailure) {
            if (!canFallbackToHaiku(firstFailure, primaryModel, fallbackModel)) {
                throw firstFailure;
            }
            log.warn(
                    "[LlmClaudeClientSupport] {} primary model failed. primaryModel={}, fallbackModel={}",
                    stepName,
                    primaryModel,
                    fallbackModel
            );
            return callClaudeWithModel(systemPrompt, userMessage, maxTokens, fallbackModel);
        }
    }

    private String resolveFallbackModel() {
        String haikuModel = llmConfig.getHaikuModel();
        if (haikuModel == null || haikuModel.isBlank()) {
            return "";
        }
        return haikuModel.trim();
    }

    private boolean canFallbackToHaiku(LlmException e, String primaryModel, String fallbackModel) {
        if (e == null) {
            return false;
        }
        if (!llmConfig.isHaikuFallbackEnabled()) {
            return false;
        }
        boolean retryable = LlmErrorCode.CLAUDE_API_CALL_FAILED.equals(e.getCode())
                || LlmErrorCode.CLAUDE_API_ERROR.equals(e.getCode());
        if (!retryable || fallbackModel == null || fallbackModel.isBlank()) {
            return false;
        }
        return primaryModel == null || !fallbackModel.equalsIgnoreCase(primaryModel.trim());
    }

    private JsonNode callClaudeWithModel(
            String systemPrompt,
            String userMessage,
            int maxTokens,
            String model
    ) {
        int effectiveMaxTokens = Math.max(1, Math.min(maxTokens, llmConfig.getMaxTokens()));
        String requestBody = buildRequestBody(sanitizeModel(model), systemPrompt, userMessage, effectiveMaxTokens);

        for (int attempt = 1; attempt <= MAX_CLAUDE_RETRY_ATTEMPTS + 1; attempt++) {
            try {
                String raw = claudeRestClient.post()
                        .uri("/v1/messages")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
                return parseResponse(raw);
            } catch (LlmException e) {
                throw e;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (!LlmResponseJsonSupport.isRetryableStatus(status) || attempt > MAX_CLAUDE_RETRY_ATTEMPTS) {
                    log.error("[LlmClaudeClientSupport] Claude API call failed. status={}, message={}", status, e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delay = LlmResponseJsonSupport.resolveRetryDelayMillis(e, attempt);
                log.warn(
                        "[LlmClaudeClientSupport] Claude API temporary failure. status={}, attempt={}/{}, retryDelayMs={}",
                        status,
                        attempt,
                        MAX_CLAUDE_RETRY_ATTEMPTS + 1,
                        delay
                );
                LlmResponseJsonSupport.sleepForRetry(delay);
            } catch (Exception e) {
                if (attempt > MAX_CLAUDE_RETRY_ATTEMPTS) {
                    log.error("[LlmClaudeClientSupport] Claude API call failed. message={}", e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                LlmResponseJsonSupport.sleepForRetry(LlmResponseJsonSupport.backoffDelayMillis(attempt));
            }
        }
        throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
    }

    private String sanitizeModel(String model) {
        if (model == null || model.isBlank()) {
            return llmConfig.getModel();
        }
        return model.trim();
    }

    private String buildRequestBody(String model, String systemPrompt, String userMessage, int maxTokens) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", maxTokens);
            root.put("system", systemPrompt);

            ArrayNode messages = root.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userMessage);

            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmErrorCode.REQUEST_SERIALIZE_FAILED);
        }
    }

    private JsonNode parseResponse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("error")) {
                log.error("[LlmClaudeClientSupport] Claude API error. message={}", root.path("error").path("message").asText("unknown"));
                throw new LlmException(LlmErrorCode.CLAUDE_API_ERROR);
            }

            String stopReason = root.path("stop_reason").asText("");
            String textContent = "";
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText(""))) {
                    textContent = block.path("text").asText("");
                    break;
                }
            }

            String jsonPayload = LlmResponseJsonSupport.stripFence(textContent.trim());
            if (jsonPayload.isBlank()) {
                throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
            }

            try {
                return objectMapper.readTree(jsonPayload);
            } catch (Exception parseFail) {
                if ("max_tokens".equalsIgnoreCase(stopReason)) {
                    log.warn("[LlmClaudeClientSupport] Claude response truncated by max_tokens.");
                }
                JsonNode recovered = LlmResponseJsonSupport.tryRecoverTruncatedJson(objectMapper, jsonPayload);
                if (recovered != null) {
                    log.warn("[LlmClaudeClientSupport] Recovered truncated response.");
                    return recovered;
                }
                throw parseFail;
            }
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LlmClaudeClientSupport] Failed to parse Claude response. raw={}", raw);
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
    }
}
