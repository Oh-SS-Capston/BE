package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.config.LlmConfig;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Claude API 호출/재시도/응답 파싱 전담 컴포넌트.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClaudeClientSupport {

    private static final int MAX_CLAUDE_RETRY_ATTEMPTS = 2;
    private static final long BASE_RETRY_DELAY_MILLIS = 1500L;
    private static final long MAX_RETRY_DELAY_MILLIS = 12000L;

    private final RestClient claudeRestClient;
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper;

    /**
     * 실행 시 사용할 1순위 모델을 결정하는 역할.
     */
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
    public JsonNode callClaudeWithHaikuFallback(
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
                if (!isRetryableStatus(status) || attempt > MAX_CLAUDE_RETRY_ATTEMPTS) {
                    log.error("[LlmClaudeClientSupport] Claude API call failed. status={}, message={}", status, e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delay = resolveRetryDelayMillis(e, attempt);
                log.warn(
                        "[LlmClaudeClientSupport] Claude API temporary failure. status={}, attempt={}/{}, retryDelayMs={}",
                        status,
                        attempt,
                        MAX_CLAUDE_RETRY_ATTEMPTS + 1,
                        delay
                );
                sleepForRetry(delay);
            } catch (Exception e) {
                if (attempt > MAX_CLAUDE_RETRY_ATTEMPTS) {
                    log.error("[LlmClaudeClientSupport] Claude API call failed. message={}", e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delay = Math.min(
                        BASE_RETRY_DELAY_MILLIS * (1L << Math.min(attempt - 1, 3)),
                        MAX_RETRY_DELAY_MILLIS
                );
                sleepForRetry(delay);
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

            String jsonPayload = stripFence(textContent.trim());
            if (jsonPayload.isBlank()) {
                throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
            }

            try {
                return objectMapper.readTree(jsonPayload);
            } catch (Exception parseFail) {
                if ("max_tokens".equalsIgnoreCase(stopReason)) {
                    log.warn("[LlmClaudeClientSupport] Claude response truncated by max_tokens.");
                }
                JsonNode recovered = tryRecoverTruncatedJson(jsonPayload);
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

    private String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text.replace("```", "").trim();
        }
        String body = text.substring(firstNewline + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body.trim();
    }

    private JsonNode tryRecoverTruncatedJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String recovered = recoverPotentiallyTruncatedJson(payload);
        if (recovered.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(recovered);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String recoverPotentiallyTruncatedJson(String payload) {
        String input = payload.trim();
        int objectStart = input.indexOf('{');
        int arrayStart = input.indexOf('[');
        int start;
        if (objectStart < 0) {
            start = arrayStart;
        } else if (arrayStart < 0) {
            start = objectStart;
        } else {
            start = Math.min(objectStart, arrayStart);
        }
        if (start < 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        int openObject = 0;
        int openArray = 0;

        for (int i = start; i < input.length(); i++) {
            char ch = input.charAt(i);
            sb.append(ch);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString && ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                openObject++;
            } else if (ch == '}') {
                openObject = Math.max(0, openObject - 1);
            } else if (ch == '[') {
                openArray++;
            } else if (ch == ']') {
                openArray = Math.max(0, openArray - 1);
            }
        }

        if (inString) {
            sb.append('"');
        }
        for (int i = 0; i < openArray; i++) {
            sb.append(']');
        }
        for (int i = 0; i < openObject; i++) {
            sb.append('}');
        }
        return sb.toString()
                .replaceAll(",\\s*}", "}")
                .replaceAll(",\\s*]", "]")
                .trim();
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private long resolveRetryDelayMillis(RestClientResponseException e, int attempt) {
        String retryAfter = null;
        if (e.getResponseHeaders() != null) {
            retryAfter = e.getResponseHeaders().getFirst("retry-after");
        }
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0) {
                    return Math.min(seconds * 1000L, MAX_RETRY_DELAY_MILLIS);
                }
            } catch (NumberFormatException ignored) {
                // 헤더 값이 비정상이면 기본 백오프로 진행한다.
            }
        }
        return Math.min(
                BASE_RETRY_DELAY_MILLIS * (1L << Math.min(attempt - 1, 3)),
                MAX_RETRY_DELAY_MILLIS
        );
    }

    private void sleepForRetry(long delayMillis) {
        try {
            Thread.sleep(Math.max(delayMillis, 0L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
        }
    }
}
