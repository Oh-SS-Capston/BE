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

    // DEFAULT_SUMMARY("핵심 동작 수행")는 제거했다.
    //
    // 빈 요약을 이 문구로 채우던 자리다. STEP 2에서 ApiDocGuideSupport.sanitizeSlot의 같은
    // 패턴을 없앴는데 여기는 남아 있었다 — 그때는 summarySeed가 이름 패턴 추측 문구로 항상
    // 차 있어서 이 분기가 거의 타지 않았기 때문이다. 시드에서 추측 문구를 걷어내자 드러났고,
    // 실측 run C의 apiEntries 32건 중 7건이 이미 이 문구를 달고 있었다.
    //
    // 근거가 없으면 빈 칸으로 둔다. 이 문구 자체는 ApiDocGuideSupport의 채움말 목록에 남겨
    // 과거 산출물 검증과 되살아남 감지를 계속한다.

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
                method.path("whatItDoes").asText("")
        ));
        String summaryNarrative = toNarrativeSummary(firstNonBlank(
                method.path("guideNarrative").asText(""),
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
        return safeText(text).replaceAll("\\s+", " ").trim();
    }

    private static String normalizeRawSummary(String text) {
        return safeText(text).replaceAll("\\s+", " ").trim();
    }

    private static String toNarrativeSummary(String rawSummary, String classFqn, String methodName) {
        String normalized = normalizeSentence(rawSummary);
        // 요약이 없으면 메서드 참조만 남은 "메서드 X#y에서 " 같은 잘린 문장을 만들지 않는다.
        if (normalized.isBlank()) {
            return "";
        }
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
        return normalizeSentence(text);
    }

    public record SummaryView(
            String summaryRaw,
            String summaryNarrative,
            String summaryPreview,
            boolean summaryTruncated
    ) {
    }
}

