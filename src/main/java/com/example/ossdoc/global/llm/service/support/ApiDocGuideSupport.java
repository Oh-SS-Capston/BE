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

    private static final int SLOT_COUNT = 5;

    /**
     * 문장 전체가 이 문구와 같을 때만 채움말로 센다.
     *
     * <p>전부 저장소 어딘가에 리터럴로 실재하는 문구다. "이런 표현도 나쁠 것 같다"는 추측 문구는
     * 넣지 않는다 — 근거 없는 판정은 근거 없는 생성과 같은 문제다. 각 항목에 출처를 달아 두었으니
     * 그 리터럴이 사라지면 여기서도 지운다.</p>
     *
     * <p>{@code inferMethodUsage}의 6개 분기를 전부 담았다. 일부만 담으면 같은 함수가 만든 문구가
     * 메서드 이름에 따라 잡히기도 하고 안 잡히기도 해서 지표가 일관성을 잃는다. 실측(junit-framework)
     * 에서 미조인 카드 23장 중 14장이 {@code :331} 문구 하나를 갖고 있었는데, 초안이 이 분기를
     * 빠뜨려 예측이 18장에서 8장으로 어긋났다.</p>
     */
    private static final List<String> FILLER_EXACT = List.of(
            "핵심 기능을 실행한다.",                                        // 이전 ApiDocGuideSupport.DEFAULT_SUMMARY (삭제됨, 과거 산출물에 남아 있다)
            "핵심 동작을 수행합니다.",                                      // LlmInputAssemblerSupport:472
            "입력 인자를 해석해 실행에 사용할 결과 객체를 만들 때 호출합니다.",   // LlmInputAssemblerSupport:325
            "실행 전에 옵션/필수값을 설정하거나 구성할 때 호출합니다.",          // LlmInputAssemblerSupport:328
            "실행 결과에서 값 존재 여부를 확인하거나 값을 읽을 때 호출합니다.",   // LlmInputAssemblerSupport:331
            "사용법 또는 오류 안내를 출력할 때 호출합니다.",                    // LlmInputAssemblerSupport:334
            "핵심 메서드를 호출한다."                                        // LlmServiceBuildSupport:562
    );

    /**
     * 문장 조각이므로 포함되기만 해도 채움말로 센다.
     * 앞에 클래스명이 붙는 형태라 전체 일치로는 잡히지 않는다.
     */
    private static final List<String> FILLER_FRAGMENT = List.of(
            "핵심 동작 수행",                                              // ApiDocSummarySupport:15
            "의 핵심 기능을 연결할 때 호출합니다",                            // LlmInputAssemblerSupport:337,340
            "핵심 흐름 중 해당 기능이 필요할 때",                             // LlmServiceBuildSupport:1593
            "입력 조건 기반 로직",
            "조건문 이후 return 또는 error response가 근접하게 나"
    );

    /**
     * 근거 없이 생성된 채움말인지 판정한다.
     *
     * <p>이 판정기 하나를 게이트(점수 계산)와 정화(빈 값 처리) 양쪽이 함께 쓴다.
     * 둘이 서로 다른 기준을 쓰면 "지표는 통과인데 산출물엔 채움말이 남는" 상태가 생긴다.</p>
     */
    public static boolean isFiller(String text) {
        String normalized = safeText(text).replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return false;
        }
        for (String exact : FILLER_EXACT) {
            if (normalized.equals(exact)) {
                return true;
            }
        }
        for (String fragment : FILLER_FRAGMENT) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 메서드 메타 정보를 바탕으로 슬롯/서술문/품질 점수를 만든다.
     */
    public static GuideView buildGuide(
            String classFqn,
            String methodName,
            String methodFqn,
            String summaryRaw,
            List<String> cautions,
            String filePath,
            Integer startLine,
            Integer endLine
    ) {
        String methodRef = buildMethodRef(classFqn, methodName, methodFqn);
        String evidenceBackedSummary = evidenceBackedSummary(summaryRaw);
        String anchor = buildEvidenceAnchor(filePath, startLine, endLine);
        String cautionMessage = firstNonBlank(cautions);

        // 근거가 있는 입력에서 나온 슬롯만 채운다.
        // doCall은 summarySeed(javadoc/구조 분석), failureSymptom은 STEP①의 caution이 근거다.
        // beforeCall/successCheck/nextAction은 메서드 이름 철자만 보고 만든 문장이라 비운다.
        String beforeCall = "";
        String doCall = sanitizeSlot(buildDoCall(methodRef, evidenceBackedSummary));
        String successCheck = "";
        String failureSymptom = sanitizeSlot(normalizeSentence(cautionMessage));
        String nextAction = "";

        GuideSlots slots = new GuideSlots(beforeCall, doCall, successCheck, failureSymptom, nextAction);
        String narrative = composeNarrative(slots);
        SlotEvidence slotEvidence = buildSlotEvidence(filePath, startLine, endLine);
        GuideQuality quality = evaluateQuality(slots, anchor, narrative, methodName, methodFqn, classFqn, filePath,
                slotEvidence.confidence());

        return new GuideView(
                evidenceBackedSummary,
                narrative,
                slots,
                quality,
                anchor,
                slotEvidence
        );
    }


    /**
     * 호출 문장을 만든다. <b>근거는 summaryRaw 하나뿐이다.</b>
     *
     * <p>예전에는 메서드 이름을 PARSE/GET_HAS/CONFIGURE 등으로 분류해 스타일별 문장을 지어냈다.
     * 이름 철자만 보고 만든 문장이라 근거가 없고, 실제로 {@code getAncestors}에
     * "parse 실행이 끝난 결과 객체를 준비하고"라는 설명이 붙는 일이 생겼다.
     * 분류를 걷어내고, summarySeed에서 온 문장이 있을 때만 슬롯을 채운다.</p>
     */
    private static String buildDoCall(String methodRef, String evidenceBackedSummary) {
        if (evidenceBackedSummary.isBlank()) {
            return "";
        }
        String subject = safeText(methodRef);
        return subject.isBlank()
                ? evidenceBackedSummary
                : subject + "를 호출한다. " + evidenceBackedSummary;
    }

    /**
     * summaryRaw가 근거 있는 문장일 때만 돌려주고, 채움말이면 빈 문자열을 준다.
     *
     * <p>{@code summaryRaw}를 "javadoc/구조 분석에서 온 값"으로 믿고 슬롯과 fallback에
     * 그대로 썼는데, 실측해 보니 미조인 카드 23장 중 22장이
     * {@code LlmInputAssemblerSupport.inferMethodUsage}의 이름 규칙 채움말이었다.
     * 걷어내기로 한 문구와 출처의 성격이 같았다.</p>
     *
     * <p>판정은 {@link #isFiller}가 한다 — 게이트가 감점에 쓰는 것과 같은 목록이다.
     * 정화와 계기판이 다른 기준을 쓰면 "지표는 통과인데 산출물엔 채움말이 남는" 상태가 생긴다.</p>
     */
    public static String evidenceBackedSummary(String summaryRaw) {
        String normalized = normalizeSentence(summaryRaw);
        return isFiller(normalized) ? "" : normalized;
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
        return scoreSlots(
                slots,
                anchor,
                computeTargetSuitabilityScore(methodName, methodFqn, classFqn, filePath),
                slotEvidenceConfidence
        );
    }

    /**
     * 가이드 슬롯 5칸의 품질 점수를 계산한다. <b>이 프로젝트의 유일한 점수 산식이다.</b>
     *
     * <p>같은 가중치가 {@code LlmServiceBuildSupport.attachGuideBundle}에도 복제되어 있었는데,
     * 두 복제본이 targetSuitability 유무로 갈라지면서 rules/cautions 경로에서 P1-3 필터가
     * 조용히 무력화됐다. 산식이 두 곳에 있으면 한 곳만 고치는 순간 산출물 간 점수 비교가
     * 무의미해지므로 여기로 모은다.</p>
     *
     * <p>가중치와 threshold는 의도적으로 그대로 둔다. 바꾸면 이전 실행의 산출물과 점수를
     * 비교할 수 없게 되고, 채움말을 걷어내는 것만으로 게이트가 살아나므로 바꿀 이유도 없다.</p>
     */
    public static GuideQuality scoreSlots(
            GuideSlots slots,
            String evidenceAnchor,
            double targetSuitability,
            String slotEvidenceConfidence
    ) {
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
        double evidenceCoverage = safeText(evidenceAnchor).isBlank() ? 0.0d : 1.0d;

        // 분모를 슬롯 수로 둔다. 이전 산식(count/2.0)은 목록이 길어지면 두 개만 걸려도 포화해
        // "조금 오염됨"과 "전부 채움말"을 구분하지 못했다. 슬롯 기준이면 slotCoverage,
        // repetitionRate와 분모가 같아져 세 지표를 나란히 읽을 수 있다.
        int fillerSlots = 0;
        for (String text : texts) {
            if (isFiller(text)) {
                fillerSlots++;
            }
        }
        double forbiddenRate = round2((double) fillerSlots / SLOT_COUNT);

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
        return new GuideQuality(actionabilityScore, slotCoverage, evidenceCoverage, forbiddenRate, repetitionRate,
                targetSuitability, slotEvidenceConfidence);
    }

    /**
     * 슬롯 문자열을 정규화한다.
     *
     * <p>예전에는 금지어와 정확히 일치하는 슬롯을 기본 문구로 바꿨는데,
     * 그 기본 문구 자체가 채움말이라 채움말을 다른 채움말로 갈아끼우는 것에 지나지 않았다.
     * 게다가 이 치환 때문에 점수 계산이 원래 문구를 못 보게 되어 게이트가 오염을 놓쳤다.
     * 치환을 걷어내고 원문을 그대로 통과시켜 {@link #scoreSlots}가 판정하게 한다.</p>
     *
     * <p>빈 값을 기본 문구로 채우는 것은 아직 남아 있다. 그것이 {@code slotCoverage}를
     * 1.0으로 고정하는 원인이지만, 걷어내는 순간 산출물이 실제로 비므로 게이트를 먼저
     * 세운 뒤 별도 단계에서 처리한다.</p>
     */
    private static String sanitizeSlot(String text) {
        return normalizeSentence(text);
    }

    /** 빈 슬롯은 건너뛴다. 그대로 이어 붙이면 공백만 남아 내용이 있는 것처럼 보인다. */
    private static String composeNarrative(GuideSlots slots) {
        StringBuilder sb = new StringBuilder();
        for (String slot : List.of(slots.beforeCall(), slots.doCall(), slots.successCheck(),
                slots.failureSymptom(), slots.nextAction())) {
            if (safeText(slot).isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(safeText(slot));
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
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

    /**
     * 공백만 정리한다. <b>빈 값을 기본 문구로 바꾸지 않는다.</b>
     *
     * <p>한 함수가 "정규화"와 "빈 값 채우기" 두 역할을 겸하고 있었고, 후자가
     * {@code slotCoverage}를 상수 1.0으로 고정해 게이트를 무력화한 원인이었다.
     * {@code evaluateQuality}의 blank 체크가 도달 불가능한 죽은 분기였던 것도 이 때문이다.</p>
     */
    private static String normalizeSentence(String text) {
        return safeText(text).replaceAll("\\s+", " ").trim();
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
