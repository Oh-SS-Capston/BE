package com.example.ossdoc.global.llm.service.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * API 메서드 설명을 실전 가이드 슬롯으로 정규화하는 유틸리티.
 */
public final class ApiDocGuideSupport {

    private ApiDocGuideSupport() {
    }

    private static final String DEFAULT_SUMMARY = "핵심 기능을 실행한다.";
    private static final int SLOT_COUNT = 5;

    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "핵심 동작 수행",
            "입력 조건 기반 로직",
            "조건문 이후 return 또는 error response가 근접하게 나"
    );

    /**
     * 메서드 타입별 문장 프레임.
     */
    private enum MethodStyle {
        PARSE,
        GET_HAS,
        CHECK_REQUIRED,
        HELP,
        CONFIGURE,
        DEFAULT
    }

    /**
     * 메서드 메타 정보를 바탕으로 슬롯/서술문/품질 점수를 만든다.
     */
    public static GuideView buildGuide(
            String classFqn,
            String methodName,
            String methodFqn,
            String summaryRaw,
            String whenToUse,
            List<String> cautions,
            String filePath,
            Integer startLine,
            Integer endLine
    ) {
        MethodStyle style = classify(methodName);
        String methodRef = buildMethodRef(classFqn, methodName, methodFqn);
        String normalizedSummary = normalizeSentence(summaryRaw);
        String anchor = buildEvidenceAnchor(filePath, startLine, endLine);
        String cautionMessage = firstNonBlank(cautions);

        String beforeCall = sanitizeSlot(buildBeforeCall(style, whenToUse));
        String doCall = sanitizeSlot(buildDoCall(style, methodRef, normalizedSummary));
        String successCheck = sanitizeSlot(buildSuccessCheck(style, methodRef));
        String failureSymptom = sanitizeSlot(buildFailureSymptom(style, cautionMessage));
        String nextAction = sanitizeSlot(buildNextAction(style, methodRef));

        GuideSlots slots = new GuideSlots(beforeCall, doCall, successCheck, failureSymptom, nextAction);
        String narrative = composeNarrative(slots);
        SlotEvidence slotEvidence = buildSlotEvidence(filePath, startLine, endLine);
        GuideQuality quality = evaluateQuality(slots, anchor, narrative, methodName, methodFqn, classFqn, filePath,
                slotEvidence.confidence());

        return new GuideView(
                normalizedSummary,
                narrative,
                slots,
                quality,
                anchor,
                slotEvidence
        );
    }

    private static MethodStyle classify(String methodName) {
        String lower = safeText(methodName).toLowerCase(Locale.ROOT);
        if (lower.contains("checkrequired") || (lower.contains("check") && lower.contains("required"))) {
            return MethodStyle.CHECK_REQUIRED;
        }
        if (lower.contains("parse")) {
            return MethodStyle.PARSE;
        }
        if (lower.startsWith("get") || lower.startsWith("has") || lower.contains("optionvalue")) {
            return MethodStyle.GET_HAS;
        }
        if (lower.contains("help") || lower.contains("print")) {
            return MethodStyle.HELP;
        }
        if (lower.startsWith("set") || lower.startsWith("add") || lower.contains("builder")) {
            return MethodStyle.CONFIGURE;
        }
        return MethodStyle.DEFAULT;
    }

    private static String buildBeforeCall(MethodStyle style, String whenToUse) {
        String timing = safeText(whenToUse);
        if (timing.isBlank()) {
            timing = "호출 전에";
        }
        return switch (style) {
            case PARSE -> timing + " 입력 인자 배열과 옵션 정의 상태를 먼저 확인한다.";
            case GET_HAS -> timing + " parse 실행이 끝난 결과 객체를 준비하고 조회 대상을 정한다.";
            case CHECK_REQUIRED -> timing + " 필수 옵션/필수 인자 등록 상태를 먼저 점검한다.";
            case HELP -> timing + " 오류 상황 또는 사용법 안내 상황인지 먼저 판단한다.";
            case CONFIGURE -> timing + " 파싱 전에 옵션 스키마와 기본값 구성을 정리한다.";
            default -> timing + " 메서드 입력값과 선행 호출 여부를 점검한다.";
        };
    }

    private static String buildDoCall(MethodStyle style, String methodRef, String summaryRaw) {
        String subject = methodRef.isBlank() ? "대상 메서드" : methodRef;
        return switch (style) {
            case PARSE -> "그다음 " + subject + "를 호출해 입력 문자열을 해석하고 파싱 결과 객체를 만든다.";
            case GET_HAS -> "그다음 " + subject + "를 호출해 옵션 존재 여부 또는 값을 조회한다.";
            case CHECK_REQUIRED -> "그다음 " + subject + "를 호출해 누락된 필수 조건이 있는지 검증한다.";
            case HELP -> "그다음 " + subject + "를 호출해 사용법/옵션 설명을 출력한다.";
            case CONFIGURE -> "그다음 " + subject + "를 호출해 옵션 정의를 구성하거나 갱신한다.";
            default -> "그다음 " + subject + "를 호출해 " + normalizeSentence(summaryRaw);
        };
    }

    private static String buildSuccessCheck(MethodStyle style, String methodRef) {
        String subject = methodRef.isBlank() ? "후속 메서드" : methodRef;
        return switch (style) {
            case PARSE -> "성공 확인은 반환 객체가 비어 있지 않고, 이후 get/has 조회가 기대값을 반환하는지로 한다.";
            case GET_HAS -> "성공 확인은 반환값이 기대한 옵션 상태와 일치하는지 비교해서 한다.";
            case CHECK_REQUIRED -> "성공 확인은 필수 조건 누락 예외가 발생하지 않고 다음 단계 호출이 이어지는지로 한다.";
            case HELP -> "성공 확인은 도움말 출력에 필수 옵션과 사용 예시가 포함되는지로 한다.";
            case CONFIGURE -> "성공 확인은 설정한 옵션이 parse 단계에서 정상 반영되는지로 한다.";
            default -> "성공 확인은 " + subject + " 호출 결과를 후속 분기 조건과 대조해서 한다.";
        };
    }

    private static String buildFailureSymptom(MethodStyle style, String cautionMessage) {
        if (!safeText(cautionMessage).isBlank()) {
            return normalizeSentence(cautionMessage);
        }
        return switch (style) {
            case PARSE -> "입력 형식 오류나 필수 인자 누락 시 파싱 예외가 발생할 수 있다.";
            case GET_HAS -> "옵션이 정의되지 않았거나 값이 없으면 null/빈 결과 또는 false가 반환될 수 있다.";
            case CHECK_REQUIRED -> "필수 옵션 누락 시 예외가 발생하고 실행 흐름이 중단될 수 있다.";
            case HELP -> "옵션 정의가 불완전하면 도움말 내용이 실제 동작과 다를 수 있다.";
            case CONFIGURE -> "옵션 구성 누락 시 이후 parse 단계에서 예상치 못한 예외가 발생할 수 있다.";
            default -> "입력값 검증이 부족하면 예외 또는 잘못된 분기가 발생할 수 있다.";
        };
    }

    private static String buildNextAction(MethodStyle style, String methodRef) {
        String subject = methodRef.isBlank() ? "해당 메서드" : methodRef;
        return switch (style) {
            case PARSE -> "실패하면 도움말 출력과 입력 토큰 구성을 점검한 뒤 parse를 다시 수행한다.";
            case GET_HAS -> "조회 실패 시 기본값 분기나 필수값 검증 분기를 먼저 적용한다.";
            case CHECK_REQUIRED -> "누락된 필수 옵션을 채운 뒤 " + subject + " 전 단계부터 다시 실행한다.";
            case HELP -> "도움말 출력 후 필수 옵션 예시를 사용자 입력 가이드에 반영한다.";
            case CONFIGURE -> "구성값을 보완하고 parse/get 계열 메서드로 검증 루프를 돌린다.";
            default -> "실패 원인을 로그와 근거 라인으로 확인하고 입력값을 수정해 재시도한다.";
        };
    }

    // P1-3: 합성 메서드·예제·내부 클래스 여부를 검사해 0.0(부적합) 또는 1.0(적합)을 반환한다.
    private static double computeTargetSuitabilityScore(String methodName, String methodFqn,
            String classFqn, String filePath) {
        String fqnToCheck = safeText(methodFqn).isBlank() ? safeText(methodName) : safeText(methodFqn);
        if (fqnToCheck.contains("lambda$") || fqnToCheck.contains("$anonymous")) return 0.0;
        String simpleName = fqnToCheck;
        int hashIdx = fqnToCheck.lastIndexOf('#');
        if (hashIdx >= 0) simpleName = fqnToCheck.substring(hashIdx + 1);
        if (simpleName.matches(".*\\$\\d+.*")) return 0.0;
        String cls = safeText(classFqn);
        if (cls.contains("$$") || cls.matches(".*\\$\\d+$") || cls.matches(".*\\$\\d+[^.]*$")) return 0.0;
        String pathNorm = safeText(filePath).replace('\\', '/').toLowerCase(Locale.ROOT);
        if (pathNorm.contains("/test/") || pathNorm.contains("/example/") || pathNorm.contains("/sample/")) return 0.0;
        return 1.0;
    }

    private static GuideQuality evaluateQuality(GuideSlots slots, String anchor, String narrative,
            String methodName, String methodFqn, String classFqn, String filePath, String slotEvidenceConfidence) {
        List<String> texts = List.of(
                slots.beforeCall(),
                slots.doCall(),
                slots.successCheck(),
                slots.failureSymptom(),
                slots.nextAction()
        );

        int filled = 0;
        for (String text : texts) {
            if (!safeText(text).isBlank()) {
                filled++;
            }
        }
        double slotCoverage = round2((double) filled / SLOT_COUNT);
        double evidenceCoverage = safeText(anchor).isBlank() ? 0.0d : 1.0d;

        int forbiddenCount = 0;
        for (String phrase : FORBIDDEN_PHRASES) {
            if (narrative.contains(phrase)) {
                forbiddenCount++;
            }
        }
        double forbiddenRate = round2(Math.min(1.0d, forbiddenCount / 2.0d));

        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String text : texts) {
            String key = normalizeForRepeat(text);
            if (key.isBlank()) {
                continue;
            }
            frequency.put(key, frequency.getOrDefault(key, 0) + 1);
        }
        int duplicates = 0;
        for (int count : frequency.values()) {
            if (count > 1) {
                duplicates += (count - 1);
            }
        }
        double repetitionRate = round2((double) duplicates / SLOT_COUNT);

        double weighted = 0.45d * slotCoverage
                + 0.25d * evidenceCoverage
                + 0.15d * (1.0d - forbiddenRate)
                + 0.15d * (1.0d - repetitionRate);

        int actionabilityScore = Math.max(0, Math.min(100, (int) Math.round(weighted * 100.0d)));
        // P1-3: targetSuitabilityScore + slotEvidenceConfidence
        double targetSuitability = computeTargetSuitabilityScore(methodName, methodFqn, classFqn, filePath);
        return new GuideQuality(actionabilityScore, slotCoverage, evidenceCoverage, forbiddenRate, repetitionRate,
                targetSuitability, slotEvidenceConfidence);
    }

    private static String sanitizeSlot(String text) {
        String normalized = normalizeSentence(text);
        if (normalized.isBlank()) {
            return DEFAULT_SUMMARY;
        }
        for (String forbidden : FORBIDDEN_PHRASES) {
            if (normalized.equals(forbidden)) {
                return DEFAULT_SUMMARY;
            }
        }
        return normalized;
    }

    private static String composeNarrative(GuideSlots slots) {
        return String.join(" ",
                slots.beforeCall(),
                slots.doCall(),
                slots.successCheck(),
                slots.failureSymptom(),
                slots.nextAction()
        ).replaceAll("\\s+", " ").trim();
    }

    private static String buildEvidenceAnchor(String filePath, Integer startLine, Integer endLine) {
        String path = safeText(filePath);
        if (path.isBlank()) {
            return "";
        }
        if (startLine == null || startLine <= 0) {
            return path;
        }
        if (endLine != null && endLine > 0 && !endLine.equals(startLine)) {
            return path + ":" + startLine + "-" + endLine;
        }
        return path + ":" + startLine;
    }

    private static SlotEvidence buildSlotEvidence(String filePath, Integer startLine, Integer endLine) {
        String path = safeText(filePath);
        String methodAnchor = buildEvidenceAnchor(filePath, startLine, endLine);
        if (path.isBlank() || startLine == null || startLine <= 0 || endLine == null || endLine < startLine) {
            return SlotEvidence.methodLevel(methodAnchor);
        }

        int lineCount = endLine - startLine + 1;
        if (lineCount < SLOT_COUNT) {
            return SlotEvidence.methodLevel(methodAnchor);
        }

        return new SlotEvidence(
                path + ":" + startLine,
                path + ":" + (startLine + Math.max(1, lineCount / 4)),
                path + ":" + (startLine + Math.max(2, lineCount / 2)),
                path + ":" + (startLine + Math.max(3, (lineCount * 3) / 4)),
                path + ":" + endLine,
                "slot_line"
        );
    }

    private static String normalizeForRepeat(String text) {
        return safeText(text)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String buildMethodRef(String classFqn, String methodName, String methodFqn) {
        String fqn = safeText(methodFqn);
        if (!fqn.isBlank()) {
            return fqn;
        }
        String owner = safeText(classFqn);
        String method = safeText(methodName);
        if (owner.isBlank() && method.isBlank()) {
            return "";
        }
        if (owner.isBlank()) {
            return method + "()";
        }
        if (method.isBlank()) {
            return owner;
        }
        return owner + "#" + method;
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        for (String value : values) {
            if (!safeText(value).isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizeSentence(String text) {
        String normalized = safeText(text).replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? DEFAULT_SUMMARY : normalized;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    public record GuideSlots(
            String beforeCall,
            String doCall,
            String successCheck,
            String failureSymptom,
            String nextAction
    ) {
    }

    public record GuideQuality(
            int actionabilityScore,
            double slotCoverage,
            double evidenceCoverage,
            double forbiddenPhraseRate,
            double repetitionRate,
            double targetSuitabilityScore,
            String slotEvidenceConfidence
    ) {
    }

    public record SlotEvidence(
            String beforeCall,
            String doCall,
            String successCheck,
            String failureSymptom,
            String nextAction,
            String confidence
    ) {
        private static SlotEvidence methodLevel(String anchor) {
            return new SlotEvidence(anchor, anchor, anchor, anchor, anchor, "method_level");
        }
    }

    public record GuideView(
            String summaryRaw,
            String narrative,
            GuideSlots slots,
            GuideQuality quality,
            String evidenceAnchor,
            SlotEvidence slotEvidence
    ) {
    }
}
