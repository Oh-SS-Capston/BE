package com.example.ossdoc.global.llm.service.support;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * API 문서 카드 요약(raw/narrative/preview/truncated) 생성 정적 유틸 클래스.
 * - 서비스 본문에서는 요약 계산을 직접 하지 않고 본 유틸을 호출한다.
 * - 동일 규칙을 coreMethods/apiEntries에 공통 적용해 출력 품질 일관성을 유지한다.
 */
public final class ApiDocSummarySupport {

    private ApiDocSummarySupport() {
    }

    private static final String DEFAULT_SUMMARY = "핵심 동작 수행";

    /**
     * coreMethodSeed 1건으로부터 요약 뷰를 만든다.
     */
    public static SummaryView fromMethodSeed(JsonNode seed, String classFqn, String methodName, int previewMaxLength) {
        String raw = normalizeRawSummary(seed.path("summarySeed").asText(""));
        return buildView(raw, classFqn, methodName, previewMaxLength);
    }

    /**
     * 이미 생성된 coreMethods 카드 1건으로부터 apiEntries용 요약 뷰를 만든다.
     */
    public static SummaryView fromMethodCard(JsonNode method, int previewMaxLength) {
        String classFqn = method.path("classFqn").asText("");
        String methodName = method.path("methodName").asText("");
        String summaryRaw = normalizeRawSummary(firstNonBlank(
                method.path("summaryRaw").asText(""),
                method.path("whatItDoesFull").asText(""),
                method.path("whatItDoes").asText(DEFAULT_SUMMARY)
        ));
        String summaryNarrative = toNarrativeSummary(firstNonBlank(
                method.path("summaryNarrative").asText(""),
                method.path("whatItDoesFull").asText(""),
                summaryRaw
        ), classFqn, methodName);
        String summaryPreview = shortenForPreview(summaryNarrative, previewMaxLength);
        boolean summaryTruncated = !summaryPreview.equals(summaryNarrative);
        return new SummaryView(summaryRaw, summaryNarrative, summaryPreview, summaryTruncated);
    }

    /**
     * raw 문자열에서 narrative/preview/truncated를 계산한다.
     */
    public static SummaryView buildView(String rawSummary, String classFqn, String methodName, int previewMaxLength) {
        String summaryRaw = normalizeRawSummary(rawSummary);
        String summaryNarrative = toNarrativeSummary(summaryRaw, classFqn, methodName);
        String summaryPreview = shortenForPreview(summaryNarrative, previewMaxLength);
        boolean summaryTruncated = !summaryPreview.equals(summaryNarrative);
        return new SummaryView(summaryRaw, summaryNarrative, summaryPreview, summaryTruncated);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizeSentence(String text) {
        String normalized = safeText(text).replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return DEFAULT_SUMMARY;
        }
        return normalized;
    }

    private static String normalizeRawSummary(String text) {
        String raw = safeText(text).replaceAll("\\s+", " ").trim();
        if (raw.isBlank()) {
            return DEFAULT_SUMMARY;
        }
        return raw;
    }

    private static String toNarrativeSummary(String rawSummary, String classFqn, String methodName) {
        String normalized = normalizeSentence(rawSummary);
        if (looksNarrativeSummary(normalized)) {
            return normalized;
        }
        String methodRef = buildMethodReference(classFqn, methodName);
        if (methodRef.isBlank()) {
            return normalized;
        }
        return "메서드 " + methodRef + "에서 " + normalized;
    }

    private static boolean looksNarrativeSummary(String summary) {
        String value = safeText(summary);
        if (value.isBlank()) {
            return false;
        }
        return value.startsWith("메서드 ")
                || value.contains("에서 ")
                || value.endsWith("입니다.")
                || value.endsWith("합니다.")
                || value.endsWith("한다.");
    }

    private static String buildMethodReference(String classFqn, String methodName) {
        String className = safeText(classFqn);
        String method = safeText(methodName);
        if (className.isBlank() && method.isBlank()) {
            return "";
        }
        if (className.isBlank()) {
            return method + "()";
        }
        if (method.isBlank()) {
            return className;
        }
        return className + "#" + method;
    }

    private static String shortenForPreview(String text, int maxLength) {
        String value = normalizeSentence(text);
        if (value.length() <= maxLength) {
            return value;
        }

        int sentenceBoundary = findSentenceBoundary(value, maxLength);
        if (sentenceBoundary >= Math.max(1, maxLength / 2)) {
            return value.substring(0, sentenceBoundary).trim() + "...";
        }

        int wordBoundary = value.lastIndexOf(' ', maxLength);
        if (wordBoundary >= Math.max(1, maxLength / 2)) {
            return value.substring(0, wordBoundary).trim() + "...";
        }

        return value.substring(0, maxLength).trim() + "...";
    }

    private static int findSentenceBoundary(String text, int limit) {
        int scanLimit = Math.min(limit, text.length() - 1);
        for (int i = scanLimit; i >= 0; i--) {
            char current = text.charAt(i);
            if (current == '.' || current == '!' || current == '?') {
                return i + 1;
            }
        }
        return -1;
    }

    public record SummaryView(
            String summaryRaw,
            String summaryNarrative,
            String summaryPreview,
            boolean summaryTruncated
    ) {
    }
}

