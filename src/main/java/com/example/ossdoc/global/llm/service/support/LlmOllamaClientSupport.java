package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.config.OllamaConfig;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 로컬 Ollama 호출/재시도/응답 파싱 전담 컴포넌트.
 *
 * <p>{@code ossdoc.llm.provider}가 ollama이거나 미지정이면 이 구현이 기본으로 등록된다.
 * 엔드포인트는 {@code POST /api/generate}(non-streaming)를 쓴다.
 * Anthropic Messages API와 달리 system/user가 별도 필드라 기존 프롬프트 구조를
 * 그대로 옮길 수 있다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ossdoc.llm.provider", havingValue = "ollama", matchIfMissing = true)
public class LlmOllamaClientSupport implements LlmChatClient {

    private static final int MAX_OLLAMA_RETRY_ATTEMPTS = 2;

    /**
     * 프롬프트 토큰 수 추정용 계수.
     *
     * <p>qwen3.5:9b 토크나이저로 실제 컨텍스트를 측정한 값은 3.27 chars/token이었다
     * (한국어 설명 + JSON 구조 혼합, 3개 구간 평균). 경고가 늦게 뜨는 것보다
     * 일찍 뜨는 편이 안전하므로 조금 보수적으로 3.0을 쓴다.</p>
     */
    private static final double CHARS_PER_TOKEN_ESTIMATE = 3.0;

    /**
     * 생성 속도 기준값(tok/s). qwen3.5:9b Q4_K_M / CPU 100%에서 실측 3.7 tok/s.
     * 타임아웃이 num_predict를 감당할 수 있는지 사전 점검하는 데만 쓴다.
     */
    private static final double BASELINE_TOKENS_PER_SECOND = 3.7;

    private final RestClient ollamaRestClient;
    private final OllamaConfig ollamaConfig;
    private final ObjectMapper objectMapper;

    /**
     * RestClient 빈이 claudeRestClient/ollamaRestClient 둘 다 존재할 수 있어
     * 타입 주입이 모호해진다. lombok.config가 없어 @Qualifier가 생성자로 복사되지 않으므로
     * 생성자를 직접 선언해 대상 빈을 명시한다.
     */
    public LlmOllamaClientSupport(
            @Qualifier("ollamaRestClient") RestClient ollamaRestClient,
            OllamaConfig ollamaConfig,
            ObjectMapper objectMapper
    ) {
        this.ollamaRestClient = ollamaRestClient;
        this.ollamaConfig = ollamaConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public String resolvePrimaryModel() {
        String model = ollamaConfig.getModel();
        return model == null ? "" : model.trim();
    }

    @Override
    public JsonNode call(
            String stepName,
            String systemPrompt,
            String userMessage,
            int maxTokens
    ) {
        String model = resolvePrimaryModel();
        int effectiveNumPredict = Math.max(1, Math.min(maxTokens, ollamaConfig.getNumPredict()));

        warnIfContextMayOverflow(stepName, systemPrompt, userMessage);
        warnIfTimeoutTooShort(stepName, effectiveNumPredict);

        log.info(
                "[LlmOllama] {} 요청. model={}, numCtx={}, numPredict={} (요청 {} / 설정 상한 {})",
                stepName,
                model,
                ollamaConfig.getNumCtx(),
                effectiveNumPredict,
                maxTokens,
                ollamaConfig.getNumPredict()
        );

        String requestBody = buildRequestBody(model, systemPrompt, userMessage, effectiveNumPredict);

        for (int attempt = 1; attempt <= MAX_OLLAMA_RETRY_ATTEMPTS + 1; attempt++) {
            try {
                String raw = ollamaRestClient.post()
                        .uri("/api/generate")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
                return parseResponse(stepName, raw);
            } catch (LlmException e) {
                throw e;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 404) {
                    // 모델 미다운로드/태그 오타는 재시도해도 동일하다. 조치 방법을 로그에 남긴다.
                    log.error(
                            "[LlmOllama] 모델을 찾을 수 없습니다. model={}. `ollama pull {}` 로 받았는지 확인하세요. message={}",
                            model,
                            model,
                            e.getMessage()
                    );
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                if (!LlmResponseJsonSupport.isRetryableStatus(status) || attempt > MAX_OLLAMA_RETRY_ATTEMPTS) {
                    log.error("[LlmOllama] 호출 실패. status={}, message={}", status, e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delay = LlmResponseJsonSupport.resolveRetryDelayMillis(e, attempt);
                log.warn(
                        "[LlmOllama] 일시적 실패. status={}, attempt={}/{}, retryDelayMs={}",
                        status,
                        attempt,
                        MAX_OLLAMA_RETRY_ATTEMPTS + 1,
                        delay
                );
                LlmResponseJsonSupport.sleepForRetry(delay);
            } catch (Exception e) {
                // 읽기 타임아웃은 재시도해도 결과가 같다. 같은 프롬프트로 같은 시간이 걸리므로
                // 재시도는 대기 시간만 3배로 늘리고 반드시 실패한다.
                // (실제로 30분 타임아웃 × 3회 = 90분을 조용히 태운 적이 있어 즉시 실패로 바꿨다.)
                if (isReadTimeout(e)) {
                    long budgetSeconds = Math.round(effectiveNumPredict / BASELINE_TOKENS_PER_SECOND);
                    log.error(
                            "[LlmOllama] {} 읽기 타임아웃({}분). 재시도하지 않습니다."
                                    + " num_predict={}이면 최소 {}초가 필요합니다."
                                    + " ollama.timeout-minutes를 늘리거나 num_predict를 줄이세요. message={}",
                            stepName,
                            ollamaConfig.getTimeoutMinutes(),
                            effectiveNumPredict,
                            budgetSeconds,
                            e.getMessage()
                    );
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                if (attempt > MAX_OLLAMA_RETRY_ATTEMPTS) {
                    // 커넥션 거부가 가장 흔하다. 데몬이 떠 있는지부터 확인하도록 안내한다.
                    log.error(
                            "[LlmOllama] 호출 실패. baseUrl={} 에 Ollama 데몬이 떠 있는지 확인하세요. message={}",
                            ollamaConfig.getBaseUrl(),
                            e.getMessage()
                    );
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delay = LlmResponseJsonSupport.backoffDelayMillis(attempt);
                // 재시도를 조용히 하지 않는다. 로그가 없으면 대기 시간이 통째로 증발한 것처럼 보인다.
                log.warn(
                        "[LlmOllama] {} 호출 예외. attempt={}/{}, retryDelayMs={}, cause={}: {}",
                        stepName,
                        attempt,
                        MAX_OLLAMA_RETRY_ATTEMPTS + 1,
                        delay,
                        e.getClass().getSimpleName(),
                        e.getMessage()
                );
                LlmResponseJsonSupport.sleepForRetry(delay);
            }
        }
        throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
    }

    /**
     * num_predict를 다 쓰면 타임아웃에 걸리는 설정인지 호출 전에 알린다.
     *
     * <p>타임아웃은 요청을 끝까지 보낸 뒤에야 드러나기 때문에, 사후 로그만 있으면
     * 수십 분을 쓰고 나서야 설정 문제였음을 알게 된다. 시작 시점에 경고한다.</p>
     */
    private void warnIfTimeoutTooShort(String stepName, int effectiveNumPredict) {
        long neededSeconds = Math.round(effectiveNumPredict / BASELINE_TOKENS_PER_SECOND);
        long timeoutSeconds = ollamaConfig.getTimeoutMinutes() * 60L;
        if (neededSeconds > timeoutSeconds) {
            log.warn(
                    "[LlmOllama] {} 타임아웃이 부족할 수 있습니다."
                            + " num_predict={}를 모두 생성하면 약 {}분({} tok/s 기준)이 필요한데"
                            + " ollama.timeout-minutes={}입니다."
                            + " 모델이 상한까지 생성하면 타임아웃으로 실패합니다.",
                    stepName,
                    effectiveNumPredict,
                    neededSeconds / 60,
                    BASELINE_TOKENS_PER_SECOND,
                    ollamaConfig.getTimeoutMinutes()
            );
        }
    }

    /**
     * 예외 사슬을 따라가 읽기 타임아웃인지 판별한다.
     * RestClient는 원인을 ResourceAccessException 등으로 감싸므로 cause까지 본다.
     */
    private boolean isReadTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * num_ctx를 넘길 것으로 보이면 경고한다.
     *
     * <p>컨텍스트 초과는 예외가 아니라 조용한 좌측 절단으로 나타나기 때문에,
     * 로그가 없으면 "모델이 스키마를 못 지킨다"로 오진하기 쉽다.</p>
     */
    private void warnIfContextMayOverflow(String stepName, String systemPrompt, String userMessage) {
        int chars = (systemPrompt == null ? 0 : systemPrompt.length())
                + (userMessage == null ? 0 : userMessage.length());
        int estimatedTokens = (int) Math.ceil(chars / CHARS_PER_TOKEN_ESTIMATE);
        if (estimatedTokens > ollamaConfig.getNumCtx()) {
            log.warn(
                    "[LlmOllama] {} 프롬프트가 num_ctx를 넘길 수 있습니다. chars={}, 추정토큰={}, numCtx={}."
                            + " 초과분은 앞에서부터 잘려 출력 스키마 지시가 사라질 수 있으니"
                            + " ossdoc.llm.generation.* 상한을 줄이거나 num_ctx를 올리세요.",
                    stepName,
                    chars,
                    estimatedTokens,
                    ollamaConfig.getNumCtx()
            );
        }
    }

    private String buildRequestBody(String model, String systemPrompt, String userMessage, int numPredict) {
        try {
            ObjectNode options = objectMapper.createObjectNode();
            options.put("num_ctx", ollamaConfig.getNumCtx());
            options.put("num_predict", numPredict);
            options.put("temperature", ollamaConfig.getTemperature());

            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("system", systemPrompt);
            root.put("prompt", userMessage);
            root.put("stream", false);
            root.put("think", ollamaConfig.isThink());
            if (ollamaConfig.isJsonFormat()) {
                root.put("format", "json");
            }
            root.set("options", options);

            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmErrorCode.REQUEST_SERIALIZE_FAILED);
        }
    }

    private JsonNode parseResponse(String stepName, String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("error")) {
                log.error("[LlmOllama] Ollama 오류 응답. message={}", root.path("error").asText("unknown"));
                throw new LlmException(LlmErrorCode.CLAUDE_API_ERROR);
            }

            logGenerationStats(stepName, root);

            String doneReason = root.path("done_reason").asText("");
            String textContent = root.path("response").asText("");

            String jsonPayload = LlmResponseJsonSupport.stripFence(
                    LlmResponseJsonSupport.stripThinkBlock(textContent)
            );
            if (jsonPayload.isBlank()) {
                throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
            }

            try {
                return objectMapper.readTree(jsonPayload);
            } catch (Exception parseFail) {
                if ("length".equalsIgnoreCase(doneReason)) {
                    log.warn("[LlmOllama] {} 응답이 num_predict 상한에서 잘렸습니다.", stepName);
                }
                JsonNode recovered = LlmResponseJsonSupport.tryRecoverTruncatedJson(objectMapper, jsonPayload);
                if (recovered != null) {
                    log.warn("[LlmOllama] {} 잘린 응답을 복원했습니다.", stepName);
                    return recovered;
                }
                throw parseFail;
            }
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LlmOllama] 응답 파싱 실패. raw={}", raw);
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
    }

    /**
     * 생성 속도/로딩 시간을 남긴다.
     * CPU 환경에서 파라미터를 조정하려면 실제 tok/s를 봐야 한다.
     */
    private void logGenerationStats(String stepName, JsonNode root) {
        long promptTokens = root.path("prompt_eval_count").asLong(0);
        long outputTokens = root.path("eval_count").asLong(0);
        long evalNanos = root.path("eval_duration").asLong(0);
        long loadNanos = root.path("load_duration").asLong(0);
        double tokensPerSecond = evalNanos > 0
                ? outputTokens / (evalNanos / 1_000_000_000.0)
                : 0.0;

        log.info(
                "[LlmOllama] {} 완료. 입력 {}토큰, 출력 {}토큰, 생성속도 {} tok/s, 모델로딩 {}초",
                stepName,
                promptTokens,
                outputTokens,
                String.format("%.2f", tokensPerSecond),
                String.format("%.1f", loadNanos / 1_000_000_000.0)
        );
    }
}
