package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * LLM 응답 텍스트를 JSON으로 되살리는 공통 정적 유틸.
 *
 * <p>Claude/Ollama 두 제공자가 동일하게 겪는 문제(코드펜스로 감싼 응답, 토큰 상한에 걸려
 * 잘린 JSON, 추론 블록 노출, 일시적 5xx)를 한 곳에서 처리한다.
 * 같은 패키지의 {@link LlmInputAssemblerSupport}와 동일한 final + static 유틸 패턴이다.</p>
 */
public final class LlmResponseJsonSupport {

    private LlmResponseJsonSupport() {
    }

    private static final long BASE_RETRY_DELAY_MILLIS = 1500L;
    private static final long MAX_RETRY_DELAY_MILLIS = 12000L;

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    /**
     * 추론(thinking) 블록을 제거한다.
     *
     * <p>qwen3 계열은 hybrid reasoning 모델이라 요청에 {@code think:false}를 줘도
     * 빌드/양자화에 따라 응답 앞에 {@code <think>...</think>}가 섞여 나올 수 있다.
     * 이 블록이 남으면 JSON 파싱이 무조건 실패하므로 파싱 전에 걷어낸다.</p>
     */
    public static String stripThinkBlock(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (!value.startsWith(THINK_OPEN)) {
            return value;
        }
        int close = value.indexOf(THINK_CLOSE);
        if (close < 0) {
            // 닫는 태그가 없다 = 출력 전체가 추론으로 소진됐다. 파싱 실패로 넘겨 재시도시킨다.
            return "";
        }
        return value.substring(close + THINK_CLOSE.length()).trim();
    }

    /**
     * 마크다운 코드펜스로 감싼 응답에서 본문만 꺼낸다.
     */
    public static String stripFence(String text) {
        if (text == null) {
            return "";
        }
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

    /**
     * 토큰 상한에 걸려 잘린 JSON을 괄호/따옴표 균형을 맞춰 복원한다.
     * 복원 불가면 null을 반환한다.
     */
    public static JsonNode tryRecoverTruncatedJson(ObjectMapper objectMapper, String payload) {
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

    /**
     * 문자열/이스케이프 상태를 추적하며 닫히지 않은 배열·객체를 닫아준다.
     *
     * <p>여는 괄호를 스택으로 추적해 <b>연 순서의 역순</b>으로 닫는다.
     * 개수만 세어 배열을 먼저 닫고 객체를 나중에 닫으면
     * {@code {"cautions":[{...}} 형태에서 {@code "]}}"}가 되어 복원 결과가 깨진다.
     * LLM 응답은 대부분 "객체 안의 배열 안의 객체" 구조라 이 순서가 중요하다.</p>
     */
    public static String recoverPotentiallyTruncatedJson(String payload) {
        String input = payload == null ? "" : payload.trim();
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
        Deque<Character> openDelimiters = new ArrayDeque<>();

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
            if (ch == '{' || ch == '[') {
                openDelimiters.push(ch);
            } else if (ch == '}') {
                if (!openDelimiters.isEmpty() && openDelimiters.peek() == '{') {
                    openDelimiters.pop();
                }
            } else if (ch == ']') {
                if (!openDelimiters.isEmpty() && openDelimiters.peek() == '[') {
                    openDelimiters.pop();
                }
            }
        }

        if (inString) {
            sb.append('"');
        }
        while (!openDelimiters.isEmpty()) {
            sb.append(openDelimiters.pop() == '{' ? '}' : ']');
        }
        return sb.toString()
                .replaceAll(",\\s*}", "}")
                .replaceAll(",\\s*]", "]")
                .trim();
    }

    /**
     * 재시도해도 의미가 있는 상태 코드인지 판정한다.
     * 404는 제외한다. 모델 태그 오타/미다운로드는 재시도로 풀리지 않는다.
     */
    public static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    /**
     * retry-after 헤더를 우선 존중하고, 없으면 지수 백오프로 대기 시간을 정한다.
     */
    public static long resolveRetryDelayMillis(RestClientResponseException e, int attempt) {
        String retryAfter = null;
        if (e != null && e.getResponseHeaders() != null) {
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
        return backoffDelayMillis(attempt);
    }

    /**
     * 1.5s에서 시작해 12s를 넘지 않는 지수 백오프.
     */
    public static long backoffDelayMillis(int attempt) {
        return Math.min(
                BASE_RETRY_DELAY_MILLIS * (1L << Math.min(Math.max(attempt - 1, 0), 3)),
                MAX_RETRY_DELAY_MILLIS
        );
    }

    public static void sleepForRetry(long delayMillis) {
        try {
            Thread.sleep(Math.max(delayMillis, 0L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
        }
    }
}
