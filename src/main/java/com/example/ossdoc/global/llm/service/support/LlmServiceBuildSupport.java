package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * LlmService의 출력 정규화/문서 조합 로직을 분리한 지원 컴포넌트.
 * 서비스 본체는 흐름 제어만 담당하고, 세부 변환은 이 클래스가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class LlmServiceBuildSupport {

    // 시나리오/문서 조합 시 사용하는 출력 제한값
    private static final int MAX_CAUTIONS = 12;
    private static final int MAX_SCENARIOS = 2;
    private static final int MAX_STEPS_PER_SCENARIO = 4;
    private static final int MAX_CORE_CLASSES = 10;
    private static final int MAX_CORE_METHODS = 18;
    private static final int MAX_METHOD_FLOW = 8;
    private static final int MAX_EVIDENCE_LINKS = 3;
    private static final int MAX_API_ENTRY_OUTPUT = 14;
    private static final int MAX_METHOD_DESCRIPTION_PREVIEW = 140;
    private static final int ACTIONABILITY_THRESHOLD = 70;
    private static final List<String> GUIDE_FORBIDDEN_PHRASES = List.of(
            "핵심 동작 수행",
            "입력 조건 기반 로직"
    );

    private static final String MAIN_JAVA_MARKER = "/src/main/java/";
    private static final String MAIN_KOTLIN_MARKER = "/src/main/kotlin/";
    private static final String TEST_MARKER = "/src/test/";
    private static final String TARGET_MARKER = "/target/";

    private final ObjectMapper objectMapper;
    /**
     * coreClassSeed에서 클래스 FQN과 소스 경로 매핑을 구성한다.
     */
    public Map<String, String> buildClassSourceMap(JsonNode coreClassSeed) {
        Map<String, String> map = new HashMap<>();
        if (!coreClassSeed.isArray()) {
            return map;
        }
        for (JsonNode cls : coreClassSeed) {
            String fqn = cls.path("fqn").asText("").trim();
            String filePath = cls.path("filePath").asText("").trim();
            if (!fqn.isBlank() && !filePath.isBlank() && isUserFacingSourcePath(filePath)) {
                map.put(fqn, filePath);
            }
        }
        return map;
    }

    /**
     * file_tree_docs 전용 coreMethods — 파일 탐색 시 소스 위치를 빠르게 찾기 위한 경량 뷰.
     * api_docs의 방법 카드(whatItDoes, inputs, returns 등)와 중복되지 않도록 위치 정보만 포함한다.
     * filePath가 없으면 classFqn으로 classSourceMap에서 역추적한다.
     */
    /**
     * file_tree_docs용 메서드 위치 요약을 생성한다.
     */
    public ArrayNode buildFileLocationMethods(JsonNode methodSeed, Map<String, String> classSourceMap) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!methodSeed.isArray()) {
            return out;
        }
        for (int i = 0; i < methodSeed.size() && out.size() < MAX_CORE_METHODS; i++) {
            JsonNode seed = methodSeed.get(i);
            String classFqn = seed.path("classFqn").asText("").trim();
            String filePath = seed.path("filePath").asText("").trim();
            if (filePath.isBlank()) {
                filePath = classSourceMap.getOrDefault(classFqn, "");
            }
            if (filePath.isBlank() || !isUserFacingSourcePath(filePath)) {
                continue;
            }
            String methodFqn = seed.path("fqn").asText("");
            String methodName = seed.path("methodName").asText("");
            Integer startLine = seed.path("startLine").canConvertToInt() ? seed.path("startLine").asInt() : null;
            Integer endLine = seed.path("endLine").canConvertToInt() ? seed.path("endLine").asInt() : null;
            String summaryRaw = normalizeSentence(seed.path("summarySeed").asText(""));

            ApiDocGuideSupport.GuideView guide = ApiDocGuideSupport.buildGuide(
                    classFqn,
                    methodName,
                    methodFqn,
                    summaryRaw,
                    inferWhenToUse(methodName),
                    List.of(),
                    filePath,
                    startLine,
                    endLine
            );

            ObjectNode item = out.addObject();
            item.put("fqn", methodFqn);
            item.put("methodName", methodName);
            item.put("classFqn", classFqn);
            item.put("filePath", filePath);
            if (startLine != null) {
                item.put("startLine", startLine);
            }
            if (endLine != null) {
                item.put("endLine", endLine);
            }
            item.put("summary", guide.narrative());
            item.put("summaryRaw", guide.summaryRaw());
            item.put("summaryNarrative", guide.narrative());
            item.put("summaryPreview", guide.narrative());
            item.put("summaryFull", guide.narrative());
            item.put("summaryTruncated", false);
            item.put("guideNarrative", guide.narrative());

            ObjectNode guideSlots = item.putObject("guideSlots");
            guideSlots.put("beforeCall", guide.slots().beforeCall());
            guideSlots.put("doCall", guide.slots().doCall());
            guideSlots.put("successCheck", guide.slots().successCheck());
            guideSlots.put("failureSymptom", guide.slots().failureSymptom());
            guideSlots.put("nextAction", guide.slots().nextAction());

            ObjectNode guideQuality = item.putObject("guideQuality");
            guideQuality.put("actionabilityScore", guide.quality().actionabilityScore());
            guideQuality.put("slotCoverage", guide.quality().slotCoverage());
            guideQuality.put("evidenceCoverage", guide.quality().evidenceCoverage());
            guideQuality.put("forbiddenPhraseRate", guide.quality().forbiddenPhraseRate());
            guideQuality.put("repetitionRate", guide.quality().repetitionRate());
            guideQuality.put("threshold", ACTIONABILITY_THRESHOLD);
            guideQuality.put("meetsThreshold", guide.quality().actionabilityScore() >= ACTIONABILITY_THRESHOLD);
            item.put("actionabilityScore", guide.quality().actionabilityScore());

            ObjectNode slotEvidence = item.putObject("slotEvidence");
            putIfText(slotEvidence, "beforeCall", guide.evidenceAnchor());
            putIfText(slotEvidence, "doCall", guide.evidenceAnchor());
            putIfText(slotEvidence, "successCheck", guide.evidenceAnchor());
            putIfText(slotEvidence, "failureSymptom", guide.evidenceAnchor());
            putIfText(slotEvidence, "nextAction", guide.evidenceAnchor());

            ObjectNode evidence = item.putObject("evidence");
            putIfText(evidence, "filePath", filePath);
            if (startLine != null) {
                evidence.put("startLine", startLine);
            }
            if (endLine != null) {
                evidence.put("endLine", endLine);
            }
            item.put("importance", seed.path("importance").asInt(0));
        }
        return out;
    }

    /**
     * 주의사항 LLM 응답을 계약 스키마로 정규화한다.
     */
    public JsonNode normalizeCautions(JsonNode raw, JsonNode structure) {
        JsonNode cautionsRaw = extractArrayByKey(raw, "cautions");
        if (cautionsRaw == null || !cautionsRaw.isArray()) {
            cautionsRaw = extractArrayByKey(raw, "rules");
        }
        if (cautionsRaw == null || !cautionsRaw.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ArrayNode cautions = objectMapper.createArrayNode();
        Set<String> dedupe = new HashSet<>();

        Map<String, ObjectNode> seedSmByCautionId = new HashMap<>();
        JsonNode seedCautions = structure.path("cautionSeed").path("cautions");
        if (seedCautions.isArray()) {
            for (JsonNode sc : seedCautions) {
                String cid  = sc.path("cautionId").asText("");
                String cond = sc.path("condition").asText("");
                String act  = sc.path("action").asText("");
                if (!cid.isBlank() && (!cond.isBlank() || !act.isBlank())) {
                    ObjectNode sm = objectMapper.createObjectNode();
                    if (!cond.isBlank()) sm.put("condition", cond);
                    if (!act.isBlank())  sm.put("action", act);
                    seedSmByCautionId.put(cid, sm);
                }
            }
        }

        for (int i = 0; i < cautionsRaw.size() && cautions.size() < MAX_CAUTIONS; i++) {
            JsonNode rawItem = cautionsRaw.get(i);
            if (!rawItem.isObject()) {
                continue;
            }

            String title = shortenText(firstNonBlank(
                    rawItem.path("title").asText(""),
                    rawItem.path("name").asText("")
            ), 70);
            String message = shortenText(firstNonBlank(
                    rawItem.path("message").asText(""),
                    rawItem.path("description").asText("")
            ), 180);

            if (title.isBlank() || message.isBlank()) {
                continue;
            }
            String key = (title + "|" + message).toLowerCase(Locale.ROOT);
            if (!dedupe.add(key)) {
                continue;
            }

            ObjectNode item = cautions.addObject();
            item.put("cautionId", String.format("CAU-%03d", cautions.size()));
            item.put("title", title);
            item.put("message", message);
            item.put("when", shortenText(rawItem.path("when").asText("호출 전 입력값과 호출 순서를 점검할 때"), 100));
            putIfText(item, "relatedClass", rawItem.path("relatedClass").asText(""));
            putIfText(item, "relatedMethod", rawItem.path("relatedMethod").asText(""));
            item.set("evidenceIds", limitEvidenceIdArray(rawItem.path("evidenceIds"), MAX_EVIDENCE_LINKS));
            item.put("confidence", normalizeConfidence(rawItem.path("confidence").asDouble(0.75d)));
            String rawCautionId = rawItem.path("cautionId").asText("");
            if (!rawCautionId.isBlank()) {
                ObjectNode seedSm = seedSmByCautionId.get(rawCautionId);
                if (seedSm != null) {
                    item.set("summary", seedSm);
                }
            }
            applyGuideToCaution(item);
        }

        if (cautions.isEmpty()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.set("cautions", cautions);
        return out;
    }

    /**
     * 시나리오 LLM 응답을 계약 스키마로 정규화한다.
     */
    public JsonNode normalizeScenarioSpecs(JsonNode raw, JsonNode structure) {
        JsonNode scenariosRaw = extractArrayByKey(raw, "scenarios");
        if (scenariosRaw == null || !scenariosRaw.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.set("overview", normalizeOverview(raw.path("overview"), structure));
        out.set("methodFlow", normalizeMethodFlow(raw.path("methodFlow"), structure.path("methodFlowSeed")));
        Map<String, JsonNode> methodSeedByFqn = indexMethodSeedByFqn(structure.path("coreMethodSeed"));
        ArrayNode flowSeed = out.path("methodFlow").isArray()
                ? (ArrayNode) out.path("methodFlow")
                : objectMapper.createArrayNode();

        ArrayNode scenarios = out.putArray("scenarios");
        for (int i = 0; i < scenariosRaw.size() && scenarios.size() < MAX_SCENARIOS; i++) {
            JsonNode rawScenario = scenariosRaw.get(i);
            if (!rawScenario.isObject()) {
                continue;
            }

            ObjectNode scenario = scenarios.addObject();
            scenario.put("scenarioId", firstNonBlank(rawScenario.path("scenarioId").asText(""), String.format("SCN-%03d", scenarios.size())));
            scenario.put("title", shortenText(rawScenario.path("title").asText("대표 사용 시나리오"), 90));
            scenario.put("intent", shortenText(rawScenario.path("intent").asText("핵심 API를 순서대로 호출해 기능을 완성한다."), 160));

            ArrayNode steps = scenario.putArray("steps");
            JsonNode rawSteps = rawScenario.path("steps");
            if (rawSteps.isArray()) {
                for (int s = 0; s < rawSteps.size() && steps.size() < MAX_STEPS_PER_SCENARIO; s++) {
                    JsonNode rawStep = rawSteps.get(s);
                    if (!rawStep.isObject()) {
                        continue;
                    }
                    JsonNode flowStep = s < flowSeed.size() ? flowSeed.get(s) : NullNode.getInstance();
                    ObjectNode step = steps.addObject();
                    step.put("stepNo", rawStep.path("stepNo").asInt(s + 1));
                    step.put("description", shortenText(rawStep.path("description").asText("핵심 메서드를 호출한다."), 160));
                    String methodFqn = firstNonBlank(
                            rawStep.path("methodFqn").asText(""),
                            flowStep.path("methodFqn").asText("")
                    );
                    String classFqn = firstNonBlank(
                            rawStep.path("classFqn").asText(""),
                            flowStep.path("classFqn").asText(""),
                            ownerFromMethodFqn(methodFqn)
                    );
                    putIfText(step, "classFqn", classFqn);
                    putIfText(step, "methodFqn", methodFqn);

                    ArrayNode evidenceLinks = normalizeEvidenceLinks(rawStep.path("evidenceLinks"), MAX_EVIDENCE_LINKS);
                    if (evidenceLinks.isEmpty()) {
                        evidenceLinks = evidenceLinksFromSeed(flowStep);
                    }
                    if (evidenceLinks.isEmpty() && !methodFqn.isBlank()) {
                        evidenceLinks = evidenceLinksFromSeed(methodSeedByFqn.getOrDefault(methodFqn, NullNode.getInstance()));
                    }
                    step.set("evidenceLinks", evidenceLinks);
                }
            }

            if (steps.isEmpty()) {
                ObjectNode step = steps.addObject();
                step.put("stepNo", 1);
                step.put("description", "핵심 API 호출 순서를 따라 실행한다.");
                JsonNode firstFlow = flowSeed.isEmpty() ? NullNode.getInstance() : flowSeed.get(0);
                String methodFqn = firstFlow.path("methodFqn").asText("");
                putIfText(step, "methodFqn", methodFqn);
                putIfText(step, "classFqn", firstFlow.path("classFqn").asText(""));
                step.set("evidenceLinks", evidenceLinksFromSeed(firstFlow));
            }
        }

        if (scenarios.isEmpty()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        return out;
    }

    /**
     * 주의사항 생성 실패 시 seed 기반 fallback을 생성한다.
     */
    public JsonNode fallbackCautions(JsonNode structure) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode cautions = out.putArray("cautions");
        JsonNode seed = structure.path("cautionSeed").path("cautions");

        if (seed.isArray()) {
            for (int i = 0; i < seed.size() && cautions.size() < MAX_CAUTIONS; i++) {
                JsonNode item = seed.get(i);
                ObjectNode caution = cautions.addObject();
                caution.put("cautionId", String.format("CAU-%03d", cautions.size()));
                caution.put("title", shortenText(item.path("title").asText("입력 검증 필요"), 70));
                caution.put("message", shortenText(item.path("message").asText("호출 전 입력값 검증이 필요합니다."), 180));
                caution.put("when", "메서드 호출 전 파라미터를 구성할 때");
                putIfText(caution, "relatedClass", item.path("relatedClass").asText(""));
                putIfText(caution, "relatedMethod", item.path("relatedMethod").asText(""));
                caution.set("evidenceIds", limitEvidenceIdArray(item.path("evidenceIds"), MAX_EVIDENCE_LINKS));
                caution.put("confidence", normalizeConfidence(item.path("confidence").asDouble(0.70d)));
                String cond = item.path("condition").asText("");
                String act  = item.path("action").asText("");
                if (!cond.isBlank() || !act.isBlank()) {
                    ObjectNode sm = objectMapper.createObjectNode();
                    if (!cond.isBlank()) sm.put("condition", cond);
                    if (!act.isBlank())  sm.put("action", act);
                    caution.set("summary", sm);
                }
                applyGuideToCaution(caution);
            }
        }

        if (cautions.isEmpty()) {
            ObjectNode caution = cautions.addObject();
            caution.put("cautionId", "CAU-001");
            caution.put("title", "필수 입력값 검증");
            caution.put("message", "필수 파라미터가 누락되면 예외가 발생할 수 있으므로 호출 전 검증하세요.");
            caution.put("when", "API 호출 전");
            caution.putArray("evidenceIds");
            caution.put("confidence", 0.60d);
            applyGuideToCaution(caution);
        }
        return out;
    }

    /**
     * 시나리오 생성 실패 시 seed 기반 fallback을 생성한다.
     */
    public JsonNode fallbackScenarioSpecs(JsonNode structure, JsonNode refinedRules) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("overview", normalizeOverview(NullNode.getInstance(), structure));
        out.set("methodFlow", normalizeMethodFlow(NullNode.getInstance(), structure.path("methodFlowSeed")));
        ArrayNode scenarios = out.putArray("scenarios");

        ObjectNode scenario = scenarios.addObject();
        scenario.put("scenarioId", "SCN-001");
        scenario.put("title", "기본 사용 흐름");
        String seedPurpose = structure.path("overviewSeed").path("purpose").asText("");
        scenario.put("intent", seedPurpose.isBlank()
                ? "핵심 API를 순서대로 호출해 기본 기능을 구현한다."
                : shortenText(seedPurpose, 120));
        ArrayNode steps = scenario.putArray("steps");

        ArrayNode flow = out.path("methodFlow").isArray() ? (ArrayNode) out.path("methodFlow") : objectMapper.createArrayNode();
        for (int i = 0; i < flow.size() && i < MAX_STEPS_PER_SCENARIO; i++) {
            JsonNode flowStep = flow.get(i);
            ObjectNode step = steps.addObject();
            step.put("stepNo", i + 1);
            step.put("description", shortenText(flowStep.path("description").asText("핵심 메서드를 순서대로 호출한다."), 160));
            putIfText(step, "classFqn", flowStep.path("classFqn").asText(""));
            putIfText(step, "methodFqn", flowStep.path("methodFqn").asText(""));
            step.set("evidenceLinks", evidenceLinksFromSeed(flowStep));
        }

        if (steps.isEmpty()) {
            ObjectNode step = steps.addObject();
            step.put("stepNo", 1);
            step.put("description", "핵심 API를 선택하고 입력값을 구성한다.");
            step.putArray("evidenceLinks");
        }
        out.put("fallbackApplied", true);
        return out;
    }

    /**
     * cautions를 하위 호환 rules 배열로 변환한다.
     */
    public JsonNode toRulesFromCautions(JsonNode cautions) {
        ArrayNode rules = objectMapper.createArrayNode();
        if (!cautions.isArray()) {
            return rules;
        }
        for (int i = 0; i < cautions.size(); i++) {
            JsonNode caution = cautions.get(i);
            ObjectNode rule = rules.addObject();
            rule.put("ruleId", String.format("RULE-%03d", i + 1));
            rule.put("name", caution.path("title").asText("주의사항"));
            rule.put("classification", "defensive");
            rule.put("description", caution.path("message").asText(""));
            JsonNode mergedFrom = caution.path("mergedFromGroups");
            rule.set("mergedFromGroups", mergedFrom.isArray() ? mergedFrom.deepCopy() : objectMapper.createArrayNode());
            rule.set("evidenceIds", limitEvidenceIdArray(caution.path("evidenceIds"), MAX_EVIDENCE_LINKS));
            rule.put("confidence", normalizeConfidence(caution.path("confidence").asDouble(0.70d)));
            applyGuideToRule(rule, caution);
        }
        return rules;
    }

    /**
     * caution 항목을 실전 가이드 슬롯 구조로 확장한다.
     */
    private void applyGuideToCaution(ObjectNode caution) {
        if (caution == null) {
            return;
        }
        String when = firstNonBlank(caution.path("when").asText(""), "API 호출 전");
        String message = normalizeSentence(caution.path("message").asText(""));
        String relatedMethod = caution.path("relatedMethod").asText("");
        String action = caution.path("summary").path("action").asText("");
        String evidenceAnchor = evidenceAnchorFromIds(caution.path("evidenceIds"));

        String beforeCall = when;
        String doCall = message;
        String successCheck = relatedMethod.isBlank()
                ? "호출 결과가 기대값과 일치하는지 확인한다."
                : relatedMethod + " 호출 결과가 기대값과 일치하는지 확인한다.";
        String failureSymptom = message;
        String nextAction = firstNonBlank(action, "입력값을 보완하고 선행 검증을 추가한 뒤 재시도한다.");

        attachGuideBundle(caution, beforeCall, doCall, successCheck, failureSymptom, nextAction, evidenceAnchor);
    }

    /**
     * rule 항목을 실전 가이드 슬롯 구조로 확장한다.
     */
    private void applyGuideToRule(ObjectNode rule, JsonNode cautionSource) {
        if (rule == null) {
            return;
        }
        String classification = rule.path("classification").asText("");
        String description = normalizeSentence(rule.path("description").asText(""));
        String ruleName = firstNonBlank(rule.path("name").asText(""), rule.path("ruleId").asText("규칙"));
        String evidenceAnchor = evidenceAnchorFromIds(rule.path("evidenceIds"));

        String beforeCall = classification.isBlank()
                ? "관련 호출 구간에서 규칙을 점검한다."
                : classification + " 규칙이 적용되는 호출 구간에서 점검한다.";
        String doCall = description.isBlank() ? ruleName + " 내용을 확인한다." : description;
        String successCheck = evidenceAnchor.isBlank()
                ? "테스트/로그에서 해당 규칙 위반 징후가 없는지 확인한다."
                : evidenceAnchor + "를 기준으로 규칙 적용 여부를 확인한다.";
        String failureSymptom = description.isBlank()
                ? "규칙 위반 시 예외 또는 잘못된 분기가 발생할 수 있다."
                : description;
        String nextAction = "위반 조건을 만족하지 않도록 입력값/호출 순서를 보완하고 다시 검증한다.";

        attachGuideBundle(rule, beforeCall, doCall, successCheck, failureSymptom, nextAction, evidenceAnchor);
        if (cautionSource != null && cautionSource.path("summary").isObject()) {
            rule.set("summary", cautionSource.path("summary").deepCopy());
        }
    }

    /**
     * 가이드 슬롯/품질 필드를 공통 형식으로 채운다.
     */
    private void attachGuideBundle(
            ObjectNode target,
            String beforeCall,
            String doCall,
            String successCheck,
            String failureSymptom,
            String nextAction,
            String evidenceAnchor
    ) {
        String before = normalizeSentence(beforeCall);
        String call = normalizeSentence(doCall);
        String success = normalizeSentence(successCheck);
        String failure = normalizeSentence(failureSymptom);
        String next = normalizeSentence(nextAction);
        String narrative = String.join(" ", before, call, success, failure, next).replaceAll("\\s+", " ").trim();

        ObjectNode slots = target.putObject("guideSlots");
        slots.put("beforeCall", before);
        slots.put("doCall", call);
        slots.put("successCheck", success);
        slots.put("failureSymptom", failure);
        slots.put("nextAction", next);

        ObjectNode slotEvidence = target.putObject("slotEvidence");
        putIfText(slotEvidence, "beforeCall", evidenceAnchor);
        putIfText(slotEvidence, "doCall", evidenceAnchor);
        putIfText(slotEvidence, "successCheck", evidenceAnchor);
        putIfText(slotEvidence, "failureSymptom", evidenceAnchor);
        putIfText(slotEvidence, "nextAction", evidenceAnchor);

        double slotCoverage = computeSlotCoverage(before, call, success, failure, next);
        double evidenceCoverage = evidenceAnchor.isBlank() ? 0.0d : 1.0d;
        double forbiddenRate = computeForbiddenPhraseRate(narrative);
        double repetitionRate = computeRepetitionRate(before, call, success, failure, next);
        double weighted = 0.45d * slotCoverage
                + 0.25d * evidenceCoverage
                + 0.15d * (1.0d - forbiddenRate)
                + 0.15d * (1.0d - repetitionRate);
        int actionabilityScore = Math.max(0, Math.min(100, (int) Math.round(weighted * 100.0d)));

        target.put("guideNarrative", narrative);
        target.put("actionabilityScore", actionabilityScore);

        ObjectNode quality = target.putObject("guideQuality");
        quality.put("actionabilityScore", actionabilityScore);
        quality.put("slotCoverage", round2(slotCoverage));
        quality.put("evidenceCoverage", round2(evidenceCoverage));
        quality.put("forbiddenPhraseRate", round2(forbiddenRate));
        quality.put("repetitionRate", round2(repetitionRate));
        quality.put("threshold", ACTIONABILITY_THRESHOLD);
        quality.put("meetsThreshold", actionabilityScore >= ACTIONABILITY_THRESHOLD);
    }

    /**
     * 5개 슬롯이 얼마나 채워졌는지(0~1) 계산한다.
     */
    private double computeSlotCoverage(String before, String call, String success, String failure, String next) {
        int filled = 0;
        for (String value : List.of(before, call, success, failure, next)) {
            if (!safeText(value).isBlank()) {
                filled++;
            }
        }
        return (double) filled / 5.0d;
    }

    /**
     * 금지 표현 포함 비율을 계산한다.
     */
    private double computeForbiddenPhraseRate(String narrative) {
        String text = safeText(narrative);
        if (text.isBlank()) {
            return 1.0d;
        }
        int count = 0;
        for (String phrase : GUIDE_FORBIDDEN_PHRASES) {
            if (text.contains(phrase)) {
                count++;
            }
        }
        return Math.min(1.0d, count / 2.0d);
    }

    /**
     * 슬롯 문장의 중복 비율을 계산한다.
     */
    private double computeRepetitionRate(String before, String call, String success, String failure, String next) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String value : List.of(before, call, success, failure, next)) {
            String key = safeText(value).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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
        return Math.min(1.0d, (double) duplicates / 5.0d);
    }

    /**
     * evidenceIds 배열을 UI 표시용 앵커 문자열로 변환한다.
     */
    private String evidenceAnchorFromIds(JsonNode evidenceIds) {
        if (evidenceIds == null || !evidenceIds.isArray()) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < evidenceIds.size() && ids.size() < 3; i++) {
            JsonNode value = evidenceIds.get(i);
            String id = value.isTextual() ? value.asText("").trim()
                    : (value.canConvertToLong() ? String.valueOf(value.asLong()) : "");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? "" : "evidenceIds: " + String.join(", ", ids);
    }

    private ObjectNode normalizeOverview(JsonNode rawOverview, JsonNode structure) {
        JsonNode seed = structure.path("overviewSeed");
        ObjectNode overview = objectMapper.createObjectNode();

        String repoName = firstNonBlank(seed.path("repoName").asText(""), "오픈소스 프로젝트");
        overview.put("project", firstNonBlank(rawOverview.path("project").asText(""), repoName));
        overview.put("purpose", firstNonBlank(
                rawOverview.path("purpose").asText(""),
                "공개 API를 빠르게 이해하고 실제 사용 순서를 안내한다."
        ));
        overview.put("fitSituation", firstNonBlank(
                rawOverview.path("fitSituation").asText(""),
                "처음 라이브러리를 도입하거나 기존 코드의 사용 흐름을 점검할 때 적합하다."
        ));
        overview.put("coreFeatures", firstNonBlank(
                rawOverview.path("coreFeatures").asText(""),
                "핵심 클래스/메서드, 사용 순서, 주의사항, 확장 포인트를 근거와 함께 제공한다."
        ));
        overview.put("startGuide", firstNonBlank(
                rawOverview.path("startGuide").asText(""),
                "대표 시나리오의 1단계부터 순서대로 실행하고, 각 단계의 메서드/근거 위치를 함께 확인한다."
        ));
        return overview;
    }

    private JsonNode normalizeMethodFlow(JsonNode rawFlow, JsonNode seedFlow) {
        ArrayNode source = objectMapper.createArrayNode();
        if (rawFlow != null && rawFlow.isArray() && !rawFlow.isEmpty()) {
            source.addAll((ArrayNode) rawFlow);
        } else if (seedFlow != null && seedFlow.isArray()) {
            source.addAll((ArrayNode) seedFlow);
        }

        ArrayNode out = objectMapper.createArrayNode();
        for (int i = 0; i < source.size() && out.size() < MAX_METHOD_FLOW; i++) {
            JsonNode raw = source.get(i);
            ObjectNode node = out.addObject();
            node.put("order", raw.path("order").asInt(i + 1));
            node.put("title", shortenText(raw.path("title").asText("단계"), 50));
            node.put("description", shortenText(raw.path("description").asText("핵심 메서드를 호출한다."), 120));
            String methodFqn = firstNonBlank(raw.path("methodFqn").asText(""), raw.path("fqn").asText(""));
            String classFqn = firstNonBlank(raw.path("classFqn").asText(""), ownerFromMethodFqn(methodFqn));
            putIfText(node, "classFqn", classFqn);
            putIfText(node, "methodFqn", methodFqn);
            String filePath = raw.path("filePath").asText("");
            if (isUserFacingSourcePath(filePath)) {
                putIfText(node, "filePath", filePath);
            }
            if (raw.path("startLine").canConvertToInt()) {
                node.put("startLine", raw.path("startLine").asInt());
            }
            if (raw.path("endLine").canConvertToInt()) {
                node.put("endLine", raw.path("endLine").asInt());
            }
        }
        return out;
    }

    /**
     * core class seed와 method seed를 결합해 클래스 문서 뷰를 만든다.
     */
    public ArrayNode buildCoreClassDocs(JsonNode classSeed, JsonNode methodSeed) {
        Map<String, List<JsonNode>> methodsByClassId = new HashMap<>();
        Map<String, List<JsonNode>> methodsByClassFqn = new HashMap<>();
        if (methodSeed.isArray()) {
            for (JsonNode method : methodSeed) {
                String classId = method.path("classSymbolId").asText("");
                if (!classId.isBlank()) {
                    methodsByClassId.computeIfAbsent(classId, k -> new ArrayList<>()).add(method);
                }
                String classFqn = method.path("classFqn").asText("");
                if (!classFqn.isBlank()) {
                    methodsByClassFqn.computeIfAbsent(classFqn, k -> new ArrayList<>()).add(method);
                }
            }
        }

        ArrayNode out = objectMapper.createArrayNode();
        if (!classSeed.isArray()) {
            return out;
        }

        for (int i = 0; i < classSeed.size() && out.size() < MAX_CORE_CLASSES; i++) {
            JsonNode seed = classSeed.get(i);
            String classId = seed.path("symbolId").asText("");
            String classFqn = seed.path("fqn").asText("");
            ObjectNode item = out.addObject();
            item.put("classId", classId);
            item.put("packageName", seed.path("packageName").asText(""));
            item.put("className", seed.path("className").asText(""));
            item.put("fqn", classFqn);
            item.put("role", seed.path("role").asText(""));
            item.put("responsibility", seed.path("role").asText(""));
            item.put("useCase", seed.path("usage").asText(""));
            item.put("importance", seed.path("importance").asInt(0));
            putIfText(item, "filePath", seed.path("filePath").asText(""));
            if (seed.path("startLine").canConvertToInt()) {
                item.put("startLine", seed.path("startLine").asInt());
            }
            if (seed.path("endLine").canConvertToInt()) {
                item.put("endLine", seed.path("endLine").asInt());
            }

            ArrayNode keyMethods = item.putArray("keyMethods");
            // classSymbolId 기준 조회 우선, 없으면 classFqn 기반 fallback
            List<JsonNode> classMethods = new ArrayList<>(methodsByClassId.getOrDefault(classId, List.of()));
            if (classMethods.isEmpty()) {
                classMethods = new ArrayList<>(methodsByClassFqn.getOrDefault(classFqn, List.of()));
            }
            classMethods.sort(Comparator.comparingInt((JsonNode m) -> m.path("importance").asInt(0)).reversed());
            for (int m = 0; m < classMethods.size() && m < 5; m++) {
                JsonNode method = classMethods.get(m);
                keyMethods.add(method.path("fqn").asText(""));
            }
            item.putArray("relatedScenarios");
        }
        return out;
    }

    /**
     * 시나리오 스텝의 classFqn/methodFqn을 교차 참조하여 각 핵심 클래스의 relatedScenarios를 채운다.
     * 직접 참조가 없는 클래스는 모든 시나리오를 fallback으로 포함한다.
     */
    /**
     * 시나리오 단계 근거를 바탕으로 각 클래스의 relatedScenarios를 채운다.
     */
    public void fillCoreClassRelatedScenarios(ArrayNode coreClasses, JsonNode scenarioSpecs) {
        JsonNode scenarios = scenarioSpecs.path("scenarios");
        if (!scenarios.isArray() || scenarios.isEmpty()) {
            return;
        }

        List<String> allScenarioIds = new ArrayList<>();
        Map<String, Set<String>> scenarioIdsByClassFqn = new LinkedHashMap<>();
        for (JsonNode scenario : scenarios) {
            String scenarioId = scenario.path("scenarioId").asText("");
            if (scenarioId.isBlank()) {
                continue;
            }
            allScenarioIds.add(scenarioId);
            JsonNode steps = scenario.path("steps");
            if (!steps.isArray()) {
                continue;
            }
            for (JsonNode step : steps) {
                String classFqn = step.path("classFqn").asText("");
                if (!classFqn.isBlank()) {
                    scenarioIdsByClassFqn.computeIfAbsent(classFqn, k -> new LinkedHashSet<>()).add(scenarioId);
                }
                String methodFqn = step.path("methodFqn").asText("");
                if (!methodFqn.isBlank()) {
                    String ownerFqn = ownerFromMethodFqn(methodFqn);
                    if (!ownerFqn.isBlank()) {
                        scenarioIdsByClassFqn.computeIfAbsent(ownerFqn, k -> new LinkedHashSet<>()).add(scenarioId);
                    }
                }
            }
        }

        for (JsonNode classNode : coreClasses) {
            if (!classNode.isObject()) {
                continue;
            }
            ObjectNode classObj = (ObjectNode) classNode;
            String fqn = classObj.path("fqn").asText("");
            Set<String> related = scenarioIdsByClassFqn.getOrDefault(fqn, Set.of());
            List<String> ids = related.isEmpty() ? allScenarioIds : new ArrayList<>(related);
            ArrayNode relatedScenarios = classObj.putArray("relatedScenarios");
            for (int i = 0; i < ids.size() && i < 3; i++) {
                relatedScenarios.add(ids.get(i));
            }
        }
    }

    /**
     * extension seed를 확장 포인트 문서 뷰로 변환한다.
     */
    public ArrayNode buildExtensionPointDocs(JsonNode extensionSeed) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!extensionSeed.isArray()) {
            return out;
        }
        for (int i = 0; i < extensionSeed.size() && i < MAX_CORE_CLASSES; i++) {
            JsonNode seed = extensionSeed.get(i);
            ObjectNode point = out.addObject();
            point.put("fqn", seed.path("fqn").asText(""));
            point.put("className", seed.path("className").asText(""));
            point.put("reason", seed.path("reason").asText(""));
            point.put("confidenceSource", seed.path("confidenceSource").asText("구조"));
            point.put("confidenceLevel", "추론".equals(seed.path("confidenceSource").asText("")) ? 0.65d : 0.85d);
            putIfText(point, "filePath", seed.path("filePath").asText(""));
        }
        return out;
    }

    /**
     * coreClasses/scenarios/rules를 조합해 서브시스템 요약을 구성한다.
     */
    public ArrayNode buildSubsystemDocs(JsonNode coreClasses, JsonNode scenarioSpecs, JsonNode refinedRules) {
        Map<String, ObjectNode> byPackage = new LinkedHashMap<>();
        if (coreClasses.isArray()) {
            for (JsonNode type : coreClasses) {
                String packageName = type.path("packageName").asText("");
                String key = topPackageKey(packageName);
                byPackage.computeIfAbsent(key, k -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("subsystemId", "ss_" + (byPackage.size() + 1));
                    node.put("label", key.isBlank() ? "core" : key);
                    node.put("description", "관련 클래스와 메서드를 묶어 사용 흐름을 설명하는 서브시스템");
                    node.put("layer", "application");
                    node.putArray("topSymbols");
                    node.putArray("relatedScenarios");
                    node.putArray("ruleIds");
                    return node;
                });
                byPackage.get(key).withArray("topSymbols").add("type:" + type.path("fqn").asText(""));
            }
        }

        ArrayNode scenarios = scenarioSpecs.path("scenarios").isArray()
                ? (ArrayNode) scenarioSpecs.path("scenarios")
                : objectMapper.createArrayNode();
        List<String> scenarioIds = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            scenarioIds.add(scenarios.get(i).path("scenarioId").asText(""));
        }

        ArrayNode rules = refinedRules.path("rules").isArray()
                ? (ArrayNode) refinedRules.path("rules")
                : objectMapper.createArrayNode();
        List<String> ruleIds = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            ruleIds.add(rules.get(i).path("ruleId").asText(""));
        }

        ArrayNode out = objectMapper.createArrayNode();
        int index = 0;
        for (ObjectNode subsystem : byPackage.values()) {
            if (index++ >= MAX_CORE_CLASSES) {
                break;
            }
            ArrayNode relatedScenarios = subsystem.withArray("relatedScenarios");
            for (int i = 0; i < scenarioIds.size() && i < 3; i++) {
                relatedScenarios.add(scenarioIds.get(i));
            }
            ArrayNode relatedRules = subsystem.withArray("ruleIds");
            for (int i = 0; i < ruleIds.size() && i < 4; i++) {
                relatedRules.add(ruleIds.get(i));
            }
            out.add(subsystem);
        }
        return out;
    }

    private String topPackageKey(String packageName) {
        String value = safeText(packageName);
        if (value.isBlank()) {
            return "core";
        }
        String[] tokens = value.split("\\.");
        if (tokens.length <= 2) {
            return value;
        }
        return tokens[0] + "." + tokens[1] + "." + tokens[2];
    }

    /**
     * 메서드 seed와 흐름/주의사항을 결합해 메서드 카드 문서를 생성한다.
     */
    public ArrayNode buildCoreMethodCards(JsonNode methodSeed, JsonNode flowSeed, JsonNode cautions) {
        Map<String, List<String>> cautionByMethod = indexCautionsByMethod(cautions);
        Map<String, Integer> orderByMethod = indexMethodOrder(flowSeed);

        ArrayNode out = objectMapper.createArrayNode();
        if (!methodSeed.isArray()) {
            return out;
        }

        for (int i = 0; i < methodSeed.size() && out.size() < MAX_CORE_METHODS; i++) {
            JsonNode seed = methodSeed.get(i);
            ObjectNode card = out.addObject();

            String fqn = seed.path("fqn").asText("");
            String methodName = seed.path("methodName").asText("");
            String classFqn = seed.path("classFqn").asText("");
            String whenToUse = inferWhenToUse(methodName);
            List<String> methodCautions = cautionByMethod.getOrDefault(fqn, List.of());
            ApiDocSummarySupport.SummaryView summary = ApiDocSummarySupport.fromMethodSeed(
                    seed,
                    classFqn,
                    methodName,
                    MAX_METHOD_DESCRIPTION_PREVIEW
            );
            ApiDocGuideSupport.GuideView guide = ApiDocGuideSupport.buildGuide(
                    classFqn,
                    methodName,
                    fqn,
                    summary.summaryRaw(),
                    whenToUse,
                    methodCautions,
                    seed.path("filePath").asText(""),
                    seed.path("startLine").canConvertToInt() ? seed.path("startLine").asInt() : null,
                    seed.path("endLine").canConvertToInt() ? seed.path("endLine").asInt() : null
            );

            card.put("methodName", methodName);
            card.put("classFqn", classFqn);
            card.put("fqn", fqn);
            card.put("summaryRaw", guide.summaryRaw());
            card.put("summaryNarrative", guide.narrative());
            card.put("summaryPreview", guide.narrative());
            card.put("summaryTruncated", false);
            card.put("whatItDoes", guide.narrative());
            card.put("whatItDoesPreview", guide.narrative());
            card.put("whatItDoesFull", guide.narrative());
            card.put("whatItDoesTruncated", false);
            card.put("guideNarrative", guide.narrative());
            card.put("whenToUse", whenToUse);

            ObjectNode guideSlots = card.putObject("guideSlots");
            guideSlots.put("beforeCall", guide.slots().beforeCall());
            guideSlots.put("doCall", guide.slots().doCall());
            guideSlots.put("successCheck", guide.slots().successCheck());
            guideSlots.put("failureSymptom", guide.slots().failureSymptom());
            guideSlots.put("nextAction", guide.slots().nextAction());

            ObjectNode guideQuality = card.putObject("guideQuality");
            guideQuality.put("actionabilityScore", guide.quality().actionabilityScore());
            guideQuality.put("slotCoverage", guide.quality().slotCoverage());
            guideQuality.put("evidenceCoverage", guide.quality().evidenceCoverage());
            guideQuality.put("forbiddenPhraseRate", guide.quality().forbiddenPhraseRate());
            guideQuality.put("repetitionRate", guide.quality().repetitionRate());
            guideQuality.put("threshold", ACTIONABILITY_THRESHOLD);
            guideQuality.put("meetsThreshold", guide.quality().actionabilityScore() >= ACTIONABILITY_THRESHOLD);
            card.put("actionabilityScore", guide.quality().actionabilityScore());

            ObjectNode slotEvidence = card.putObject("slotEvidence");
            putIfText(slotEvidence, "beforeCall", guide.evidenceAnchor());
            putIfText(slotEvidence, "doCall", guide.evidenceAnchor());
            putIfText(slotEvidence, "successCheck", guide.evidenceAnchor());
            putIfText(slotEvidence, "failureSymptom", guide.evidenceAnchor());
            putIfText(slotEvidence, "nextAction", guide.evidenceAnchor());
            card.put("inputs", extractInputs(seed.path("signatureHint").asText("")));
            card.put("returns", extractReturns(seed.path("signatureHint").asText("")));
            card.put("changesState", inferStateChange(methodName));
            card.set("pairedWith", inferPairedMethods(methodName, methodSeed));
            card.put("callOrderNotes", formatCallOrderNote(orderByMethod.get(fqn)));
            card.set("cautions", toTextArray(methodCautions));
            card.put("importance", seed.path("importance").asInt(0));

            ObjectNode evidence = card.putObject("evidence");
            putIfText(evidence, "filePath", seed.path("filePath").asText(""));
            if (seed.path("startLine").canConvertToInt()) {
                evidence.put("startLine", seed.path("startLine").asInt());
            }
            if (seed.path("endLine").canConvertToInt()) {
                evidence.put("endLine", seed.path("endLine").asInt());
            }
        }
        return out;
    }

    private Map<String, List<String>> indexCautionsByMethod(JsonNode cautions) {
        Map<String, List<String>> out = new HashMap<>();
        if (!cautions.isArray()) {
            return out;
        }
        for (JsonNode caution : cautions) {
            String method = caution.path("relatedMethod").asText("");
            if (method.isBlank()) {
                continue;
            }
            out.computeIfAbsent(method, k -> new ArrayList<>())
                    .add(caution.path("message").asText(""));
        }
        return out;
    }

    private Map<String, Integer> indexMethodOrder(JsonNode flowSeed) {
        Map<String, Integer> out = new HashMap<>();
        if (!flowSeed.isArray()) {
            return out;
        }
        for (JsonNode step : flowSeed) {
            String method = step.path("methodFqn").asText("");
            if (method.isBlank()) {
                continue;
            }
            out.put(method, step.path("order").asInt(0));
        }
        return out;
    }

    private String inferWhenToUse(String methodName) {
        String lower = safeText(methodName).toLowerCase(Locale.ROOT);
        if (lower.contains("parse")) {
            return "옵션 정의가 끝난 뒤 실제 입력(args)을 해석할 때";
        }
        if (lower.contains("add") || lower.contains("required") || lower.contains("builder")) {
            return "애플리케이션 시작 시 옵션 스키마를 정의할 때";
        }
        if (lower.contains("get") || lower.contains("has")) {
            return "파싱 완료 후 값을 읽거나 분기 처리할 때";
        }
        if (lower.contains("help") || lower.contains("print")) {
            return "오류 처리 또는 사용법 안내를 출력할 때";
        }
        return "핵심 흐름 중 해당 기능이 필요할 때";
    }

    private String extractInputs(String signatureHint) {
        if (signatureHint == null || signatureHint.isBlank()) {
            return "입력 파라미터는 소속 클래스의 시그니처를 따른다.";
        }
        int idx = signatureHint.indexOf("->");
        if (idx < 0) {
            return shortenText(signatureHint, 120);
        }
        return shortenText(signatureHint.substring(0, idx).trim(), 120);
    }

    private String extractReturns(String signatureHint) {
        if (signatureHint == null || signatureHint.isBlank()) {
            return "반환 타입은 소속 메서드 시그니처를 따른다.";
        }
        int idx = signatureHint.indexOf("->");
        if (idx < 0) {
            return "반환 타입 정보가 시드에 명확하지 않음(추정)";
        }
        return shortenText(signatureHint.substring(idx + 2).trim(), 80);
    }

    private boolean inferStateChange(String methodName) {
        String lower = safeText(methodName).toLowerCase(Locale.ROOT);
        return lower.startsWith("set")
                || lower.startsWith("add")
                || lower.contains("build")
                || lower.contains("parse");
    }

    private ArrayNode inferPairedMethods(String methodName, JsonNode methodSeed) {
        ArrayNode out = objectMapper.createArrayNode();
        String lower = safeText(methodName).toLowerCase(Locale.ROOT);
        List<String> keywords = new ArrayList<>();
        if (lower.contains("parse")) {
            keywords = List.of("add", "required", "get", "has");
        } else if (lower.contains("add") || lower.contains("required")) {
            keywords = List.of("parse");
        } else if (lower.contains("get") || lower.contains("has")) {
            keywords = List.of("parse");
        } else if (lower.contains("help") || lower.contains("print")) {
            keywords = List.of("parse");
        }

        if (keywords.isEmpty() || !methodSeed.isArray()) {
            return out;
        }
        for (JsonNode seed : methodSeed) {
            String peer = seed.path("fqn").asText("");
            String peerName = seed.path("methodName").asText("").toLowerCase(Locale.ROOT);
            if (peer.isBlank() || peerName.equals(lower)) {
                continue;
            }
            for (String keyword : keywords) {
                if (peerName.contains(keyword)) {
                    out.add(peer);
                    break;
                }
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private String formatCallOrderNote(Integer order) {
        if (order == null || order <= 0) {
            return "호출 순서는 시나리오 문맥에 따라 달라질 수 있음";
        }
        return "권장 호출 순서 단계: " + order;
    }

    /**
     * 메서드 사용 순서 문서를 구성한다.
     */
    public ArrayNode buildMethodUsageOrder(JsonNode flowSeed, JsonNode scenarios) {
        ArrayNode out = objectMapper.createArrayNode();
        if (flowSeed.isArray()) {
            for (int i = 0; i < flowSeed.size() && out.size() < MAX_METHOD_FLOW; i++) {
                JsonNode seed = flowSeed.get(i);
                ObjectNode item = out.addObject();
                item.put("order", seed.path("order").asInt(i + 1));
                item.put("title", seed.path("title").asText("단계"));
                item.put("description", seed.path("description").asText("메서드를 호출한다."));
                putIfText(item, "methodFqn", seed.path("methodFqn").asText(""));
                putIfText(item, "classFqn", seed.path("classFqn").asText(""));
            }
            return out;
        }

        // flow seed가 없으면 시나리오에서 추출
        if (scenarios != null && scenarios.isArray() && !scenarios.isEmpty()) {
            JsonNode firstScenario = scenarios.get(0).path("steps");
            if (firstScenario.isArray()) {
                for (int i = 0; i < firstScenario.size() && out.size() < MAX_METHOD_FLOW; i++) {
                    JsonNode step = firstScenario.get(i);
                    ObjectNode item = out.addObject();
                    item.put("order", i + 1);
                    item.put("title", "시나리오 단계");
                    item.put("description", step.path("description").asText("메서드를 호출한다."));
                    putIfText(item, "methodFqn", step.path("methodFqn").asText(""));
                    putIfText(item, "classFqn", step.path("classFqn").asText(""));
                }
            }
        }
        return out;
    }

    /**
     * 하위 호환용 apiEntries 배열을 생성한다.
     */
    public ArrayNode buildApiEntriesCompat(JsonNode coreMethods) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!coreMethods.isArray()) {
            return out;
        }
        for (int i = 0; i < coreMethods.size() && out.size() < MAX_API_ENTRY_OUTPUT; i++) {
            JsonNode method = coreMethods.get(i);
            ObjectNode entry = out.addObject();
            entry.put("fqn", method.path("fqn").asText(""));
            ApiDocSummarySupport.SummaryView summary = ApiDocSummarySupport.fromMethodCard(
                    method,
                    MAX_METHOD_DESCRIPTION_PREVIEW
            );
            entry.put("summary", firstNonBlank(
                    method.path("guideNarrative").asText(""),
                    summary.summaryNarrative()
            ));
            entry.put("summaryRaw", summary.summaryRaw());
            entry.put("summaryNarrative", summary.summaryNarrative());
            entry.put("summaryPreview", summary.summaryPreview());
            entry.put("summaryFull", summary.summaryNarrative());
            entry.put("summaryTruncated", summary.summaryTruncated());
            entry.put("guideNarrative", method.path("guideNarrative").asText(""));
            if (method.path("guideSlots").isObject()) {
                entry.set("guideSlots", method.path("guideSlots").deepCopy());
            }
            if (method.path("guideQuality").isObject()) {
                entry.set("guideQuality", method.path("guideQuality").deepCopy());
            }
            if (method.path("slotEvidence").isObject()) {
                entry.set("slotEvidence", method.path("slotEvidence").deepCopy());
            }
            entry.put("actionabilityScore", method.path("actionabilityScore").asInt(0));
            entry.put("subsystem", shortenText(method.path("classFqn").asText("core"), 80));
            ArrayNode relatedScenarios = entry.putArray("relatedScenarios");
            relatedScenarios.add("SCN-001");
        }
        return out;
    }

    /**
     * API 카드의 실전 가이드 품질 점수를 집계한다.
     */
    public ObjectNode buildApiDocQualityGate(JsonNode coreMethods) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("threshold", ACTIONABILITY_THRESHOLD);
        out.put("metric", "actionabilityScore");

        if (!coreMethods.isArray() || coreMethods.isEmpty()) {
            out.put("methodCount", 0);
            out.put("averageActionabilityScore", 0.0d);
            out.put("minActionabilityScore", 0);
            out.put("belowThresholdCount", 0);
            out.put("slotCoverageAvg", 0.0d);
            out.put("evidenceCoverageAvg", 0.0d);
            out.put("forbiddenPhraseRateAvg", 0.0d);
            out.put("repetitionRateAvg", 0.0d);
            out.put("meetsThreshold", false);
            return out;
        }

        int count = 0;
        int totalScore = 0;
        int minScore = Integer.MAX_VALUE;
        int belowThreshold = 0;
        double slotCoverageSum = 0.0d;
        double evidenceCoverageSum = 0.0d;
        double forbiddenRateSum = 0.0d;
        double repetitionRateSum = 0.0d;

        for (int i = 0; i < coreMethods.size(); i++) {
            JsonNode method = coreMethods.get(i);
            JsonNode quality = method.path("guideQuality");
            int score = quality.path("actionabilityScore").asInt(method.path("actionabilityScore").asInt(0));
            double slotCoverage = quality.path("slotCoverage").asDouble(0.0d);
            double evidenceCoverage = quality.path("evidenceCoverage").asDouble(0.0d);
            double forbiddenRate = quality.path("forbiddenPhraseRate").asDouble(0.0d);
            double repetitionRate = quality.path("repetitionRate").asDouble(0.0d);

            count++;
            totalScore += score;
            minScore = Math.min(minScore, score);
            if (score < ACTIONABILITY_THRESHOLD) {
                belowThreshold++;
            }
            slotCoverageSum += slotCoverage;
            evidenceCoverageSum += evidenceCoverage;
            forbiddenRateSum += forbiddenRate;
            repetitionRateSum += repetitionRate;
        }

        out.put("methodCount", count);
        out.put("averageActionabilityScore", round2((double) totalScore / count));
        out.put("minActionabilityScore", minScore == Integer.MAX_VALUE ? 0 : minScore);
        out.put("belowThresholdCount", belowThreshold);
        out.put("slotCoverageAvg", round2(slotCoverageSum / count));
        out.put("evidenceCoverageAvg", round2(evidenceCoverageSum / count));
        out.put("forbiddenPhraseRateAvg", round2(forbiddenRateSum / count));
        out.put("repetitionRateAvg", round2(repetitionRateSum / count));
        out.put("meetsThreshold", belowThreshold == 0);
        return out;
    }

    /**
     * file_tree_docs의 메서드 가이드 품질 점수를 집계한다.
     */
    public ObjectNode buildFileTreeDocQualityGate(JsonNode coreMethods) {
        return buildApiDocQualityGate(coreMethods);
    }

    /**
     * 정제 규칙(rules/cautions)의 가이드 품질 점수를 집계한다.
     */
    public ObjectNode buildRefinedRuleQualityGate(JsonNode rules, JsonNode cautions) {
        ArrayNode merged = objectMapper.createArrayNode();
        if (rules != null && rules.isArray()) {
            merged.addAll((ArrayNode) rules);
        }
        if (cautions != null && cautions.isArray()) {
            merged.addAll((ArrayNode) cautions);
        }
        ObjectNode gate = buildApiDocQualityGate(merged);
        gate.put("ruleCount", countArray(rules));
        gate.put("cautionCount", countArray(cautions));
        return gate;
    }

    /**
     * 배열 노드 개수를 안전하게 계산한다.
     */
    private int countArray(JsonNode source) {
        return source != null && source.isArray() ? source.size() : 0;
    }

    private ArrayNode normalizeEvidenceLinks(JsonNode links, int maxCount) {
        ArrayNode out = objectMapper.createArrayNode();
        if (links == null || !links.isArray()) {
            return out;
        }
        for (int i = 0; i < links.size() && out.size() < maxCount; i++) {
            JsonNode link = links.get(i);
            if (!link.isObject()) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            if (link.path("evidenceId").canConvertToLong()) {
                item.put("evidenceId", link.path("evidenceId").asLong());
            }
            String filePath = safeText(link.path("filePath").asText(""));
            if (!filePath.isBlank() && !isUserFacingSourcePath(filePath)) {
                continue;
            }
            putIfText(item, "filePath", filePath);
            putIfText(item, "lines", firstNonBlank(
                    link.path("lines").asText(""),
                    formatLines(link.path("startLine"), link.path("endLine"))
            ));
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private ArrayNode evidenceLinksFromSeed(JsonNode seed) {
        ArrayNode out = objectMapper.createArrayNode();
        if (seed == null || seed.isMissingNode() || seed.isNull()) {
            return out;
        }
        String filePath = safeText(seed.path("filePath").asText(""));
        if (!isUserFacingSourcePath(filePath)) {
            return out;
        }
        ObjectNode link = out.addObject();
        putIfText(link, "filePath", filePath);
        putIfText(link, "lines", firstNonBlank(
                seed.path("lines").asText(""),
                formatLines(seed.path("startLine"), seed.path("endLine"))
        ));
        return out;
    }

    private Map<String, JsonNode> indexMethodSeedByFqn(JsonNode methodSeed) {
        Map<String, JsonNode> out = new HashMap<>();
        if (methodSeed == null || !methodSeed.isArray()) {
            return out;
        }
        for (JsonNode seed : methodSeed) {
            String fqn = safeText(seed.path("fqn").asText(""));
            if (!fqn.isBlank()) {
                out.putIfAbsent(fqn, seed);
            }
        }
        return out;
    }

    private String ownerFromMethodFqn(String methodFqn) {
        String value = safeText(methodFqn);
        if (value.isBlank()) {
            return "";
        }
        if (value.contains("#")) {
            return value.substring(0, value.indexOf('#'));
        }
        int idx = value.lastIndexOf('.');
        if (idx <= 0) {
            return "";
        }
        return value.substring(0, idx);
    }

    private boolean isUserFacingSourcePath(String filePath) {
        String normalized = normalizePath(filePath);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains(TEST_MARKER) || normalized.contains(TARGET_MARKER)) {
            return false;
        }
        // leading slash 없이 시작하는 상대 경로(e.g. "src/main/java/...")도 인식
        String withSlash = normalized.startsWith("/") ? normalized : "/" + normalized;
        return withSlash.contains(MAIN_JAVA_MARKER) || withSlash.contains(MAIN_KOTLIN_MARKER);
    }

    private String normalizePath(String path) {
        return safeText(path).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String formatLines(JsonNode startLine, JsonNode endLine) {
        if (startLine == null || startLine.isMissingNode() || startLine.isNull()) {
            return "";
        }
        if (endLine != null && endLine.canConvertToInt() && startLine.canConvertToInt()) {
            int start = startLine.asInt();
            int end = endLine.asInt();
            return start == end ? String.valueOf(start) : start + "-" + end;
        }
        return startLine.asText("");
    }

    /**
     * cautions 생성 프롬프트용 컨텍스트 JSON 문자열을 구성한다.
     */
    public String buildCautionContext(JsonNode structure, List<LlmRequest.EvidenceSnippet> evidence) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("overviewSeed", structure.path("overviewSeed"));
        context.set("cautionSeed", structure.path("cautionSeed"));
        context.set("coreMethodSeed", takeFirst(structure.path("coreMethodSeed"), 12));
        context.set("qualityGate", structure.path("qualityGate"));
        context.set("evidence", toEvidenceNode(evidence, 12));
        return toJson(context);
    }

    /**
     * scenario 생성 프롬프트용 컨텍스트 JSON 문자열을 구성한다.
     */
    public String buildScenarioContext(
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("overviewSeed", structure.path("overviewSeed"));
        context.set("coreClassSeed", takeFirst(structure.path("coreClassSeed"), 8));
        context.set("coreMethodSeed", takeFirst(structure.path("coreMethodSeed"), 14));
        context.set("methodFlowSeed", takeFirst(structure.path("methodFlowSeed"), 6));
        context.set("cautions", takeFirst(refinedRules.path("cautions"), 8));
        context.set("evidence", toEvidenceNode(evidence, 12));
        return toJson(context);
    }

    private ArrayNode takeFirst(JsonNode arrayNode, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        if (arrayNode == null || !arrayNode.isArray()) {
            return out;
        }
        for (int i = 0; i < arrayNode.size() && i < limit; i++) {
            out.add(arrayNode.get(i));
        }
        return out;
    }

    private JsonNode toEvidenceNode(List<LlmRequest.EvidenceSnippet> evidence, int maxCount) {
        ArrayNode out = objectMapper.createArrayNode();
        if (evidence == null || evidence.isEmpty()) {
            return out;
        }
        for (LlmRequest.EvidenceSnippet item : evidence) {
            if (out.size() >= maxCount || item == null) {
                break;
            }
            ObjectNode node = out.addObject();
            if (item.getEvidenceId() != null) {
                node.put("evidenceId", item.getEvidenceId());
            }
            putIfText(node, "filePath", shortenText(item.getFilePath(), 220));
            if (item.getStartLine() != null) {
                if (item.getEndLine() != null) {
                    node.put("lines", item.getStartLine() + "-" + item.getEndLine());
                } else {
                    node.put("lines", String.valueOf(item.getStartLine()));
                }
            }
            putIfText(node, "snippet", shortenText(item.getSnippet(), 120));
            putIfText(node, "evidenceType", shortenText(item.getEvidenceType(), 40));
        }
        return out;
    }

    private ArrayNode limitEvidenceIdArray(JsonNode source, int maxCount) {
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        if (source == null || !source.isArray()) {
            return out;
        }
        for (int i = 0; i < source.size() && out.size() < maxCount; i++) {
            JsonNode value = source.get(i);
            String id = value.isTextual() ? value.asText("").trim()
                    : (value.canConvertToLong() ? String.valueOf(value.asLong()) : "");
            if (!id.isBlank() && seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private ArrayNode toTextArray(List<String> values) {
        ArrayNode out = objectMapper.createArrayNode();
        for (String value : values) {
            String normalized = shortenText(value, 120);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private JsonNode extractArrayByKey(JsonNode raw, String key) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return NullNode.getInstance();
        }
        if (raw.isArray()) {
            return raw;
        }
        JsonNode value = raw.path(key);
        return value.isArray() ? value : NullNode.getInstance();
    }

    private boolean isResponseParseFailed(LlmException e) {
        return e != null && LlmErrorCode.RESPONSE_PARSE_FAILED.equals(e.getCode());
    }

    private double normalizeConfidence(double value) {
        if (Double.isNaN(value) || value <= 0.0d) {
            return 0.70d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return Math.round(value * 100.0d) / 100.0d;
    }

    private double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String applyLanguagePolicy(String basePrompt) {
        return basePrompt + "\n\n언어 정책:\n" + LlmPromptCatalog.KOREAN_POLICY;
    }

    private void putIfText(ObjectNode node, String key, String value) {
        if (node == null || key == null || value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isBlank()) {
            node.put(key, trimmed);
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String shortenText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value;
    }

    private String normalizeSentence(String text) {
        String normalized = safeText(text).replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "핵심 동작 수행";
        }
        return normalized;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }
}




