package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.ossdoc.global.llm.config.LlmGenerationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LlmService의 출력 정규화/문서 조합 로직을 분리한 지원 컴포넌트.
 * 서비스 본체는 흐름 제어만 담당하고, 세부 변환은 이 클래스가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmServiceBuildSupport {

    // 시나리오/문서 조합 시 사용하는 출력 제한값
    //
    // caution/scenario/step 상한은 ossdoc.llm.generation.* 설정에서 읽는다.
    // 프롬프트에 "최대 N개"라고 써도 로컬 9B 모델은 이를 무시하므로(실측: 8을 요구했으나 20개 생성),
    // 실제 개수를 결정하는 것은 여기의 상한이다. 설정과 실동작이 어긋나지 않도록 같은 값을 쓴다.
    private static final int MAX_CORE_CLASSES = 20;
    private static final int MAX_CORE_METHODS = 40;
    private static final int MAX_METHOD_FLOW = 16;
    private static final int MAX_EVIDENCE_LINKS = 8;
    private static final int MAX_API_ENTRY_OUTPUT = 32;
    private static final int MAX_METHOD_DESCRIPTION_PREVIEW = 400;
    private static final int ACTIONABILITY_THRESHOLD = 70;
    private static final List<String> OVERVIEW_RICH_TEXT_FIELDS = List.of(
            "architectureSummary",
            "dataFlow",
            "confidenceNote"
    );
    private static final List<String> CAUTION_RICH_TEXT_FIELDS = List.of(
            "impact",
            "rationale",
            "normalFlow",
            "successSignal",
            "failureSignal",
            "userAction",
            "evidenceInterpretation",
            "confidenceReason"
    );
    private static final List<String> SCENARIO_RICH_TEXT_FIELDS = List.of(
            "whyThisMatters",
            "entryPoint",
            "expectedOutcome",
            "confidenceReason"
    );
    /**
     * 서술에서 코드 심볼로 볼 토큰. humps가 둘 이상인 CamelCase만 잡는다.
     * 한 겹까지 허용하면 한국어 사이에 섞인 일반 명사와 {@code API} 같은 약어를 오탐한다.
     */
    private static final Pattern CAMEL_CASE_SYMBOL =
            Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+\\b");

    private static final List<String> STEP_RICH_TEXT_FIELDS = List.of(
            "precondition",
            "action",
            "successSignal",
            "failureSignal",
            "userAction",
            "dataHandled",
            "evidenceInterpretation",
            "confidenceReason"
    );
    private static final List<String> METHOD_FLOW_RICH_TEXT_FIELDS = List.of(
            "precondition",
            "result",
            "risk",
            "evidenceInterpretation",
            "confidenceReason"
    );
    /**
     * targetSuitability를 측정할 수 없는 경로임을 나타내는 값.
     *
     * <p>{@code attachGuideBundle}은 rules/cautions/step이 공유하는 경로라
     * 메서드 문맥(methodName/classFqn/filePath)을 항상 갖고 있지 않다.
     * 억지로 계산해 넣는 대신 <b>guideQuality에 아예 쓰지 않고</b>,
     * 집계하는 {@code buildApiDocQualityGate}가 부재를 "측정 불가"로 읽게 한다.
     * 예전에는 부재를 1.0(적합)으로 읽어 P1-3 필터가 조용히 항상 통과했다.</p>
     */
    private static final double TARGET_SUITABILITY_NOT_MEASURED = -1.0d;

    /**
     * 시나리오 서술 채움률 기준선.
     *
     * <p>0.9로 둔 근거는 실측이다 — 호출 분해 전(SINGLE) 0.41, 분해 후(PER_SCENARIO) 0.97이라
     * 두 상태를 확실히 가른다. 완주를 요구하되 한두 칸의 미충족은 허용하는 선이다.</p>
     */
    private static final double SCENARIO_NARRATIVE_THRESHOLD = 0.9d;

    private static final String MAIN_JAVA_MARKER = "/src/main/java/";
    private static final String MAIN_KOTLIN_MARKER = "/src/main/kotlin/";
    private static final String TEST_MARKER = "/src/test/";
    private static final String TARGET_MARKER = "/target/";

    private final ObjectMapper objectMapper;
    private final LlmGenerationProperties llmGenerationProperties;
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
                    List.of(),
                    filePath,
                    startLine,
                    endLine
            );

            // 오버로딩을 화면에서 구분할 수 있도록 시그니처(파라미터 포함)를 함께 노출한다.
            String signatureHint = seed.path("signatureHint").asText("");
            String displaySignature = signatureHint.isBlank() ? methodName + "()" : extractInputs(signatureHint);

            ObjectNode item = out.addObject();
            item.put("fqn", methodFqn);
            item.put("methodName", methodName);
            putIfText(item, "signatureHint", signatureHint);
            item.put("displaySignature", displaySignature);
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
            guideQuality.put("targetSuitabilityScore", guide.quality().targetSuitabilityScore());
            guideQuality.put("slotEvidenceConfidence", guide.quality().slotEvidenceConfidence());
            guideQuality.put("threshold", ACTIONABILITY_THRESHOLD);
            guideQuality.put("meetsThreshold", guide.quality().actionabilityScore() >= ACTIONABILITY_THRESHOLD
                    && guide.quality().targetSuitabilityScore() >= 1.0);
            item.put("actionabilityScore", guide.quality().actionabilityScore());

            ObjectNode slotEvidence = item.putObject("slotEvidence");
            writeSlotEvidence(slotEvidence, guide.slotEvidence());

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

        for (int i = 0; i < cautionsRaw.size() && cautions.size() < llmGenerationProperties.getMaxCautions(); i++) {
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
            copyTextFields(rawItem, item, CAUTION_RICH_TEXT_FIELDS);
            if (rawItem.path("summary").isObject()) {
                item.set("summary", rawItem.path("summary").deepCopy());
            }
            item.put("when", shortenText(rawItem.path("when").asText("호출 전 입력값과 호출 순서를 점검할 때"), 100));
            putIfText(item, "relatedClass", rawItem.path("relatedClass").asText(""));
            putIfText(item, "relatedMethod", rawItem.path("relatedMethod").asText(""));
            item.set("evidenceIds", limitEvidenceIdArray(rawItem.path("evidenceIds"), MAX_EVIDENCE_LINKS));
            item.put("confidence", normalizeConfidence(rawItem.path("confidence").asDouble(0.75d)));
            String rawCautionId = rawItem.path("cautionId").asText("");
            if (!rawCautionId.isBlank()) {
                ObjectNode seedSm = seedSmByCautionId.get(rawCautionId);
                if (seedSm != null && !item.has("summary")) {
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
     * PER_SCENARIO 모드: overview와 methodFlow만 담은 껍데기를 만든다.
     *
     * <p>{@code scenarios}는 빈 배열로 두고 호출자가 시나리오별 호출 결과를 하나씩 채운다.
     * methodFlow는 시드 결정론이라 LLM 호출이 필요 없다 — {@code methodFlowSeed}가
     * 이미 실제 호출 순서를 갖고 있고, SINGLE 모드에서도 모델 응답보다 시드가 우선이다.</p>
     */
    public ObjectNode buildScenarioSpecsShell(JsonNode overviewRaw, JsonNode structure) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("overview", normalizeOverview(overviewRaw, structure));
        out.set("methodFlow", normalizeMethodFlow(NullNode.getInstance(), structure.path("methodFlowSeed")));
        out.putArray("scenarios");
        return out;
    }

    /**
     * PER_SCENARIO 모드: 골격 한 장과 그 호출의 응답으로 시나리오 하나를 만든다.
     *
     * <p>SINGLE 모드와 같은 {@link #buildScenariosFromSeed}를 원소 하나짜리 배열로 태운다.
     * 근거 우선순위(골격이 이기고 서술은 모델이 이긴다)와 폐기 계측을 그대로 쓰기 위해서다.
     * 두 모드의 산출물이 같은 코드를 통과해야 A/B 비교가 성립한다.</p>
     *
     * @throws LlmException 응답에 시나리오가 없으면 compact 재시도 기회를 남긴다.
     */
    public JsonNode normalizeOneScenario(JsonNode seedScenario, JsonNode raw, JsonNode structure) {
        JsonNode scenariosRaw = extractArrayByKey(raw, "scenarios");
        if (scenariosRaw == null || !scenariosRaw.isArray() || scenariosRaw.isEmpty()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        ArrayNode seedOnly = objectMapper.createArrayNode();
        seedOnly.add(seedScenario);

        ArrayNode built = buildScenariosFromSeed(
                seedOnly,
                scenariosRaw,
                indexMethodSeedByFqn(structure.path("coreMethodSeed"))
        );
        return built.isEmpty() ? NullNode.getInstance() : built.get(0);
    }

    /**
     * PER_SCENARIO 모드 실패 시: 모델 응답 없이 골격만으로 시나리오 하나를 만든다.
     * 시나리오 하나가 실패해도 나머지는 살아남는다는 것이 분해의 이점이므로,
     * 여기서 전체를 포기하지 않고 그 한 장만 시드 문구로 되돌린다.
     */
    public JsonNode fallbackOneScenario(JsonNode seedScenario, JsonNode structure) {
        ArrayNode seedOnly = objectMapper.createArrayNode();
        seedOnly.add(seedScenario);

        ArrayNode built = buildScenariosFromSeed(
                seedOnly,
                objectMapper.createArrayNode(),
                indexMethodSeedByFqn(structure.path("coreMethodSeed"))
        );
        return built.isEmpty() ? NullNode.getInstance() : built.get(0);
    }

    /**
     * 시나리오 LLM 응답을 계약 스키마로 정규화한다.
     */
    public JsonNode normalizeScenarioSpecs(JsonNode raw, JsonNode structure) {
        JsonNode scenariosRaw = extractArrayByKey(raw, "scenarios");
        if (scenariosRaw == null || !scenariosRaw.isArray() || scenariosRaw.isEmpty()) {
            // 응답에 시나리오가 아예 없으면 compact 재시도 기회를 남긴다.
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.set("overview", normalizeOverview(raw.path("overview"), structure));
        out.set("methodFlow", normalizeMethodFlow(raw.path("methodFlow"), structure.path("methodFlowSeed")));
        Map<String, JsonNode> methodSeedByFqn = indexMethodSeedByFqn(structure.path("coreMethodSeed"));
        ArrayNode flowSeed = out.path("methodFlow").isArray()
                ? (ArrayNode) out.path("methodFlow")
                : objectMapper.createArrayNode();

        JsonNode scenarioSeed = structure.path("scenarioSeed");
        if (scenarioSeed.isArray() && !scenarioSeed.isEmpty()) {
            out.set("scenarios", buildScenariosFromSeed(scenarioSeed, scenariosRaw, methodSeedByFqn));
            return out;
        }

        ArrayNode scenarios = out.putArray("scenarios");
        for (int i = 0; i < scenariosRaw.size() && scenarios.size() < llmGenerationProperties.getMaxScenarios(); i++) {
            JsonNode rawScenario = scenariosRaw.get(i);
            if (!rawScenario.isObject()) {
                continue;
            }

            ObjectNode scenario = scenarios.addObject();
            scenario.put("scenarioId", firstNonBlank(rawScenario.path("scenarioId").asText(""), String.format("SCN-%03d", scenarios.size())));
            scenario.put("title", shortenText(rawScenario.path("title").asText("대표 사용 시나리오"), 90));
            scenario.put("intent", shortenText(rawScenario.path("intent").asText("핵심 API를 순서대로 호출해 기능을 완성한다."), 160));

            copyTextFields(rawScenario, scenario, SCENARIO_RICH_TEXT_FIELDS);

            ArrayNode steps = scenario.putArray("steps");
            JsonNode rawSteps = rawScenario.path("steps");
            if (rawSteps.isArray()) {
                for (int s = 0; s < rawSteps.size() && steps.size() < llmGenerationProperties.getMaxStepsPerScenario(); s++) {
                    JsonNode rawStep = rawSteps.get(s);
                    if (!rawStep.isObject()) {
                        continue;
                    }
                    JsonNode flowStep = s < flowSeed.size() ? flowSeed.get(s) : NullNode.getInstance();
                    ObjectNode step = steps.addObject();
                    step.put("stepNo", rawStep.path("stepNo").asInt(s + 1));
                    step.put("description", shortenText(rawStep.path("description").asText("핵심 메서드를 호출한다."), 160));
                    copyTextFields(rawStep, step, STEP_RICH_TEXT_FIELDS);
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
                    attachStepGuideBundle(step);
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
                attachStepGuideBundle(step);
            }
        }

        if (scenarios.isEmpty()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        return out;
    }

    /**
     * 시나리오 골격(scenarioSeed)을 축으로 모델 서술을 덮어 시나리오를 만든다.
     *
     * <p>모델 응답을 축으로 삼으면 시나리오 개수/순서/대상 메서드가 실행마다 흔들리고,
     * 누락된 단계는 {@code methodFlow}의 같은 인덱스를 가져다 쓰게 되어 서로 다른
     * 시나리오의 step 1이 모두 같은 메서드를 참조하는 문제가 있었다.
     * 골격을 축으로 삼으면 구조는 코드가 보장하고 모델은 서술만 담당한다.</p>
     *
     * <p>필드별 우선순위가 다르다. scenarioId/classFqn/methodFqn/근거 위치는 골격이 이기고,
     * 서술 필드는 모델이 이긴다. 모델이 대상을 바꿔치기해도 골격이 되돌린다.</p>
     */
    private ArrayNode buildScenariosFromSeed(
            JsonNode scenarioSeed,
            JsonNode scenariosRaw,
            Map<String, JsonNode> methodSeedByFqn
    ) {
        Map<String, JsonNode> rawById = new HashMap<>();
        for (JsonNode rawScenario : scenariosRaw) {
            if (!rawScenario.isObject()) {
                continue;
            }
            String id = safeText(rawScenario.path("scenarioId").asText(""));
            if (!id.isBlank()) {
                rawById.putIfAbsent(id, rawScenario);
            }
        }

        // 골격 밖 응답은 여기서 조용히 사라진다. 얼마나 버리는지 모르면
        // 모델이 프롬프트를 지키는지 측정할 방법이 없다.
        Set<JsonNode> consumedScenarios = Collections.newSetFromMap(new IdentityHashMap<>());
        int seedStepSlots = 0;
        int slotsFilledByModel = 0;
        int rawStepsSeen = 0;
        int rawStepsConsumed = 0;

        ArrayNode scenarios = objectMapper.createArrayNode();
        for (int i = 0; i < scenarioSeed.size(); i++) {
            JsonNode seedScenario = scenarioSeed.get(i);
            String scenarioId = firstNonBlank(
                    seedScenario.path("scenarioId").asText(""),
                    String.format("SCN-%03d", i + 1)
            );
            // scenarioId로 먼저 맞추고, 모델이 ID를 바꿨으면 같은 순서의 응답으로 맞춘다.
            JsonNode rawScenario = rawById.get(scenarioId);
            if (rawScenario == null) {
                rawScenario = i < scenariosRaw.size() ? scenariosRaw.get(i) : NullNode.getInstance();
            }
            if (rawScenario.isObject()) {
                consumedScenarios.add(rawScenario);
            }

            ObjectNode scenario = scenarios.addObject();
            scenario.put("scenarioId", scenarioId);
            scenario.put("title", firstNonBlank(
                    rawScenario.path("title").asText(""),
                    seedScenario.path("title").asText(""),
                    "대표 사용 시나리오"
            ));
            scenario.put("intent", firstNonBlank(
                    rawScenario.path("intent").asText(""),
                    seedScenario.path("intent").asText(""),
                    "핵심 API를 순서대로 호출해 기능을 완성한다."
            ));
            copyTextFields(rawScenario, scenario, SCENARIO_RICH_TEXT_FIELDS);

            ArrayNode steps = scenario.putArray("steps");
            JsonNode seedSteps = seedScenario.path("steps");
            JsonNode rawSteps = rawScenario.path("steps");
            Map<Integer, JsonNode> rawStepByNo = indexStepsByNo(rawSteps);
            if (rawSteps.isArray()) {
                rawStepsSeen += rawSteps.size();
            }

            for (int s = 0; s < seedSteps.size(); s++) {
                JsonNode seedStep = seedSteps.get(s);
                int stepNo = seedStep.path("stepNo").asInt(s + 1);
                JsonNode rawStep = rawStepByNo.get(stepNo);
                if (rawStep == null) {
                    rawStep = rawSteps.isArray() && s < rawSteps.size() ? rawSteps.get(s) : NullNode.getInstance();
                }
                seedStepSlots++;
                if (rawStep.isObject()) {
                    rawStepsConsumed++;
                    if (!safeText(rawStep.path("description").asText("")).isBlank()) {
                        slotsFilledByModel++;
                    }
                }

                ObjectNode step = steps.addObject();
                step.put("stepNo", stepNo);
                step.put("description", firstNonBlank(
                        rawStep.path("description").asText(""),
                        seedStep.path("summarySeed").asText(""),
                        "핵심 메서드를 호출한다."
                ));
                copyTextFields(rawStep, step, STEP_RICH_TEXT_FIELDS);

                String methodFqn = firstNonBlank(
                        seedStep.path("methodFqn").asText(""),
                        rawStep.path("methodFqn").asText("")
                );
                String classFqn = firstNonBlank(
                        seedStep.path("classFqn").asText(""),
                        rawStep.path("classFqn").asText(""),
                        ownerFromMethodFqn(methodFqn)
                );
                putIfText(step, "classFqn", classFqn);
                putIfText(step, "methodFqn", methodFqn);

                // 근거 위치는 골격이 우선이다. 모델이 지어낸 evidenceId를 신뢰하지 않는다.
                ArrayNode evidenceLinks = evidenceLinksFromSeed(seedStep);
                if (evidenceLinks.isEmpty()) {
                    evidenceLinks = normalizeEvidenceLinks(rawStep.path("evidenceLinks"), MAX_EVIDENCE_LINKS);
                }
                if (evidenceLinks.isEmpty() && !methodFqn.isBlank()) {
                    evidenceLinks = evidenceLinksFromSeed(methodSeedByFqn.getOrDefault(methodFqn, NullNode.getInstance()));
                }
                step.set("evidenceLinks", evidenceLinks);
                attachStepGuideBundle(step);
            }

            // 두 모드가 같은 경로에서 재야 A/B가 성립한다. SINGLE은 시나리오마다 한 번씩,
            // PER_SCENARIO는 호출마다 한 번씩 찍혀 같은 단위로 비교된다.
            logOffSkeletonSymbolMentions(seedScenario, scenario);
        }

        logSeedAxisDrops(scenariosRaw, consumedScenarios, seedStepSlots, slotsFilledByModel,
                rawStepsSeen, rawStepsConsumed);
        return scenarios;
    }

    /**
     * 서술이 이 시나리오 골격 밖의 심볼을 얼마나 끌어오는지 센다.
     *
     * <p><b>이것은 hallucination 지표가 아니다.</b> 재는 것은 "골격 밖 방황이 시나리오 바깥에서
     * 안으로 위치를 옮겼는가"뿐이다. PER_SCENARIO로 쪼개면 프롬프트에 시나리오가 한 장뿐이라
     * {@code logSeedAxisDrops}의 폐기 수가 구조적으로 0에 수렴하는데, 그때 방황이 사라진 건지
     * 시나리오 <i>안쪽</i>으로 들어간 건지 구분할 지표가 없어진다. 그 자리를 메운다.</p>
     *
     * <p>못 잡는 것이 분명히 있다. "앞서 생성한 객체를 이어서" 같은 대명사적 서술은 심볼을
     * 쓰지 않으므로 0으로 센다. 그건 이 지표가 아니라 {@code excludedByOtherScenarios}로
     * 막는다. 이 값이 낮다고 "지어내지 않았다"로 읽으면 안 된다.</p>
     *
     * <p>탐지는 humps가 둘 이상인 CamelCase로 한정한다({@code MediaType}, {@code VintageExecutor}).
     * 한국어 산문과 {@code API} 같은 약어를 오탐하지 않는 선이다.</p>
     */
    private void logOffSkeletonSymbolMentions(JsonNode seedScenario, ObjectNode builtScenario) {
        Set<String> allowed = new HashSet<>();
        for (JsonNode step : seedScenario.path("steps")) {
            collectSymbolNames(allowed, step.path("classFqn").asText(""));
            collectSymbolNames(allowed, step.path("methodFqn").asText(""));
        }

        Map<String, Integer> offSkeleton = new LinkedHashMap<>();
        int scannedChars = 0;
        for (String field : SCENARIO_RICH_TEXT_FIELDS) {
            scannedChars += countMentions(builtScenario.path(field).asText(""), allowed, offSkeleton);
        }
        for (JsonNode step : builtScenario.path("steps")) {
            scannedChars += countMentions(step.path("description").asText(""), allowed, offSkeleton);
            for (String field : STEP_RICH_TEXT_FIELDS) {
                scannedChars += countMentions(step.path(field).asText(""), allowed, offSkeleton);
            }
        }

        if (offSkeleton.isEmpty()) {
            log.info(
                    "[LlmScenario] 방황 이동 없음. {} 서술 {}자에 골격 밖 심볼 언급 0건.",
                    builtScenario.path("scenarioId").asText(""), scannedChars
            );
            return;
        }
        int total = offSkeleton.values().stream().mapToInt(Integer::intValue).sum();
        log.warn(
                "[LlmScenario] 방황 이동 감지. {} 서술 {}자에 골격 밖 심볼 {}건: {}."
                        + " (hallucination 지표가 아니라 방황 위치 이동 여부만 잰다)",
                builtScenario.path("scenarioId").asText(""), scannedChars, total, offSkeleton
        );
    }

    /** FQN에서 사람이 서술에 쓸 법한 이름(단순 타입명, 메서드명)을 뽑아 허용 집합에 넣는다. */
    private void collectSymbolNames(Set<String> target, String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return;
        }
        for (String part : fqn.split("[.#]")) {
            if (!part.isBlank()) {
                target.add(part);
            }
        }
    }

    /** 텍스트에서 CamelCase 심볼을 찾아 허용 집합 밖인 것만 센다. 반환값은 훑은 글자 수다. */
    private int countMentions(String text, Set<String> allowed, Map<String, Integer> offSkeleton) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = CAMEL_CASE_SYMBOL.matcher(text);
        while (matcher.find()) {
            String symbol = matcher.group();
            if (!allowed.contains(symbol)) {
                offSkeleton.merge(symbol, 1, Integer::sum);
            }
        }
        return text.length();
    }

    /**
     * 골격 축 정규화가 버린 응답 분량을 남긴다.
     *
     * <p>모델이 골격 밖 시나리오를 만들면 여기서 전량 폐기되는데, 지금까지는 조용히 사라져서
     * 프롬프트 준수 여부를 측정할 방법이 없었다. 5차 실행 기준으로 STEP② 출력의 약 58%가
     * 이 경로로 버려지고 있었다. 이 수치가 0에 수렴하는지가 프롬프트 수정의 성공 판정이다.</p>
     *
     * <p>PER_SCENARIO 모드에서는 프롬프트에 시나리오가 한 장뿐이라 이 값이 구조적으로 낮아진다.
     * 그것만 보고 "방황이 사라졌다"고 읽으면 안 된다 — 짝이 되는 지표가
     * {@link #logOffSkeletonSymbolMentions}다.</p>
     */
    private void logSeedAxisDrops(
            JsonNode scenariosRaw,
            Set<JsonNode> consumedScenarios,
            int seedStepSlots,
            int slotsFilledByModel,
            int rawStepsSeen,
            int rawStepsConsumed
    ) {
        int droppedScenarios = 0;
        for (JsonNode rawScenario : scenariosRaw) {
            if (rawScenario.isObject() && !consumedScenarios.contains(rawScenario)) {
                droppedScenarios++;
            }
        }
        int droppedSteps = Math.max(0, rawStepsSeen - rawStepsConsumed);

        if (droppedScenarios > 0 || droppedSteps > 0) {
            log.warn(
                    "[LlmScenario] 골격 밖 응답 폐기. 시나리오 {}/{}개, step {}/{}개."
                            + " 모델이 골격에 없는 항목을 만드는 만큼 서술 예산이 줄어든다.",
                    droppedScenarios, scenariosRaw.size(), droppedSteps, rawStepsSeen
            );
        }
        log.info(
                "[LlmScenario] 골격 채움. {}/{} 칸을 모델 서술로 채웠다 (나머지는 시드 문구).",
                slotsFilledByModel, seedStepSlots
        );
    }

    private Map<Integer, JsonNode> indexStepsByNo(JsonNode rawSteps) {
        Map<Integer, JsonNode> out = new HashMap<>();
        if (rawSteps == null || !rawSteps.isArray()) {
            return out;
        }
        for (JsonNode rawStep : rawSteps) {
            if (rawStep.isObject() && rawStep.path("stepNo").canConvertToInt()) {
                out.putIfAbsent(rawStep.path("stepNo").asInt(), rawStep);
            }
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
            for (int i = 0; i < seed.size() && cautions.size() < llmGenerationProperties.getMaxCautions(); i++) {
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

        // 골격이 있으면 모델 서술만 빠진 형태로 되돌린다. 시나리오 구조는 그대로 남는다.
        JsonNode scenarioSeed = structure.path("scenarioSeed");
        if (scenarioSeed.isArray() && !scenarioSeed.isEmpty()) {
            out.set("scenarios", buildScenariosFromSeed(
                    scenarioSeed,
                    objectMapper.createArrayNode(),
                    indexMethodSeedByFqn(structure.path("coreMethodSeed"))
            ));
            out.put("fallbackApplied", true);
            return out;
        }

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
        for (int i = 0; i < flow.size() && i < llmGenerationProperties.getMaxStepsPerScenario(); i++) {
            JsonNode flowStep = flow.get(i);
            ObjectNode step = steps.addObject();
            step.put("stepNo", i + 1);
            step.put("description", shortenText(flowStep.path("description").asText("핵심 메서드를 순서대로 호출한다."), 160));
            putIfText(step, "classFqn", flowStep.path("classFqn").asText(""));
            putIfText(step, "methodFqn", flowStep.path("methodFqn").asText(""));
            step.set("evidenceLinks", evidenceLinksFromSeed(flowStep));
            attachStepGuideBundle(step);
        }

        if (steps.isEmpty()) {
            ObjectNode step = steps.addObject();
            step.put("stepNo", 1);
            step.put("description", "핵심 API를 선택하고 입력값을 구성한다.");
            step.putArray("evidenceLinks");
            attachStepGuideBundle(step);
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
            copyTextFields(caution, rule, CAUTION_RICH_TEXT_FIELDS);
            if (caution.path("summary").isObject()) {
                rule.set("summary", caution.path("summary").deepCopy());
            }
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
        String normalFlow = caution.path("normalFlow").asText("");
        String successSignal = caution.path("successSignal").asText("");
        String failureSignal = caution.path("failureSignal").asText("");
        String userAction = caution.path("userAction").asText("");
        String evidenceInterpretation = caution.path("evidenceInterpretation").asText("");

        String beforeCall = when;
        String doCall = message;
        String successCheck = relatedMethod.isBlank()
                ? "호출 결과가 기대값과 일치하는지 확인한다."
                : relatedMethod + " 호출 결과가 기대값과 일치하는지 확인한다.";
        String failureSymptom = message;
        String nextAction = firstNonBlank(action, "입력값을 보완하고 선행 검증을 추가한 뒤 재시도한다.");

        beforeCall = firstNonBlank(normalFlow, beforeCall);
        doCall = firstNonBlank(userAction, doCall);
        successCheck = firstNonBlank(successSignal, evidenceInterpretation, successCheck);
        failureSymptom = firstNonBlank(failureSignal, failureSymptom);
        nextAction = firstNonBlank(action, userAction, evidenceInterpretation, nextAction);

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
    private void attachStepGuideBundle(ObjectNode step) {
        if (step == null) {
            return;
        }
        String description = step.path("description").asText("");
        String beforeCall = firstNonBlank(step.path("precondition").asText(""), description);
        String doCall = firstNonBlank(step.path("action").asText(""), description);
        String successCheck = firstNonBlank(step.path("successSignal").asText(""), step.path("evidenceInterpretation").asText(""));
        String failureSymptom = firstNonBlank(step.path("failureSignal").asText(""), step.path("confidenceReason").asText(""));
        String nextAction = firstNonBlank(step.path("userAction").asText(""), step.path("evidenceInterpretation").asText(""));
        attachGuideBundle(step, beforeCall, doCall, successCheck, failureSymptom, nextAction, evidenceAnchorFromLinks(step.path("evidenceLinks")));
    }

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
        slotEvidence.put("slotEvidenceConfidence", "method_level");

        // 산식은 ApiDocGuideSupport 한 곳에만 둔다. 여기 복제본이 targetSuitability를
        // 빠뜨리는 바람에 buildApiDocQualityGate가 기본값 1.0으로 읽어 P1-3 필터가
        // rules/cautions 경로에서 통째로 무력화돼 있었다.
        ApiDocGuideSupport.GuideSlots guideSlots =
                new ApiDocGuideSupport.GuideSlots(before, call, success, failure, next);
        ApiDocGuideSupport.GuideQuality scored = ApiDocGuideSupport.scoreSlots(
                guideSlots, evidenceAnchor, TARGET_SUITABILITY_NOT_MEASURED, "method_level");
        int actionabilityScore = scored.actionabilityScore();

        target.put("guideNarrative", narrative);
        target.put("actionabilityScore", actionabilityScore);

        ObjectNode quality = target.putObject("guideQuality");
        quality.put("actionabilityScore", actionabilityScore);
        quality.put("slotCoverage", round2(scored.slotCoverage()));
        quality.put("evidenceCoverage", round2(scored.evidenceCoverage()));
        quality.put("forbiddenPhraseRate", round2(scored.forbiddenPhraseRate()));
        quality.put("repetitionRate", round2(scored.repetitionRate()));
        quality.put("slotEvidenceConfidence", "method_level");
        quality.put("threshold", ACTIONABILITY_THRESHOLD);
        // 채움말이 하나라도 섞이면 점수와 무관하게 탈락시킨다. 근거 없는 문구를 담은 항목이
        // "기준 통과"로 표시되면 게이트가 위반을 측정하는 게 아니라 허가하는 도구가 된다.
        quality.put("meetsThreshold",
                actionabilityScore >= ACTIONABILITY_THRESHOLD && scored.forbiddenPhraseRate() == 0.0d);
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

    private String evidenceAnchorFromLinks(JsonNode evidenceLinks) {
        if (evidenceLinks == null || !evidenceLinks.isArray()) {
            return "";
        }
        List<String> anchors = new ArrayList<>();
        for (int i = 0; i < evidenceLinks.size() && anchors.size() < 3; i++) {
            JsonNode link = evidenceLinks.get(i);
            String evidenceId = link.path("evidenceId").isMissingNode()
                    ? ""
                    : link.path("evidenceId").asText("");
            String filePath = link.path("filePath").asText("");
            String lines = link.path("lines").asText("");
            String anchor = firstNonBlank(
                    evidenceId.isBlank() ? "" : "evidenceId: " + evidenceId,
                    filePath.isBlank() ? "" : filePath + (lines.isBlank() ? "" : ":" + lines)
            );
            if (!anchor.isBlank()) {
                anchors.add(anchor);
            }
        }
        return anchors.isEmpty() ? "" : String.join(", ", anchors);
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
        copyTextFields(rawOverview, overview, OVERVIEW_RICH_TEXT_FIELDS);
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
            copyTextFields(raw, node, METHOD_FLOW_RICH_TEXT_FIELDS);
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
    public ArrayNode buildCoreMethodCards(JsonNode methodSeed, JsonNode flowSeed, JsonNode cautions, JsonNode scenarios) {
        Map<String, List<String>> cautionByMethod = indexCautionsByMethod(cautions);
        Map<String, Integer> orderByMethod = indexMethodOrder(flowSeed);
        Map<String, JsonNode> scenarioStepByMethod = indexScenarioStepByMethod(scenarios);

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
            List<String> methodCautions = cautionByMethod.getOrDefault(fqn, List.of());
            JsonNode scenarioStep = scenarioStepByMethod.getOrDefault(fqn, NullNode.getInstance());
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
                    methodCautions,
                    seed.path("filePath").asText(""),
                    seed.path("startLine").canConvertToInt() ? seed.path("startLine").asInt() : null,
                    seed.path("endLine").canConvertToInt() ? seed.path("endLine").asInt() : null
            );

            card.put("methodName", methodName);
            card.put("classFqn", classFqn);
            card.put("fqn", fqn);
            card.put("summaryRaw", guide.summaryRaw());
            attachMethodGuideBundle(card, guide, scenarioStep);
            mergeGuideOnlyQualityFields(card, guide);
            // 슬롯이 비면 narrative도 비고, 그때 fallback 대상은 summaryRaw다.
            // 그런데 미조인 카드의 summaryRaw 대부분이 이름 규칙 채움말이었으므로
            // 그대로 떨어뜨릴 수 없다. guide.summaryRaw()는 이미 정화된 값이라
            // 채움말이면 빈 문자열이고, 비는 것이 사실에 맞는 상태다.
            String guideNarrative = firstNonBlank(
                    card.path("guideNarrative").asText(""),
                    guide.narrative(),
                    guide.summaryRaw());
            card.put("summaryNarrative", guideNarrative);
            card.put("summaryPreview", guideNarrative);
            card.put("summaryTruncated", false);
            card.put("whatItDoes", guideNarrative);
            card.put("whatItDoesPreview", guideNarrative);
            card.put("whatItDoesFull", guideNarrative);
            card.put("whatItDoesTruncated", false);

            ObjectNode slotEvidence = card.putObject("slotEvidence");
            writeSlotEvidence(slotEvidence, guide.slotEvidence());

            attachUsageScenario(card, scenarioStep);
            String signatureHint = seed.path("signatureHint").asText("");
            // 오버로딩 구분용 표시 시그니처(메서드명 + 파라미터). fqn은 단순명을 유지한다.
            card.put("displaySignature", signatureHint.isBlank() ? methodName + "()" : extractInputs(signatureHint));
            card.put("inputs", extractInputs(signatureHint));
            card.put("returns", extractReturns(signatureHint));
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

    /**
     * ApiDocGuideSupport에서만 계산되는 지표를 메서드 카드에 얹는다.
     *
     * <p>예전에는 {@code attachMethodGuideBundle} 바로 다음에서 guideSlots/guideQuality를
     * {@code putObject}로 통째로 다시 써서, 방금 병합한 시나리오 서술이 전부 사라졌다.
     * STEP②가 아무리 좋은 서술을 만들어도 STEP④ 산출물에는 결정론적 기본 문구만 남았고,
     * {@code attachMethodGuideBundle}의 슬롯 병합은 사실상 죽은 코드였다.
     * 이제는 덮어쓰지 않고 빠진 값만 채운다.</p>
     *
     * <p>{@code targetSuitabilityScore}는 이 경로에서만 계산된다. 빠뜨리면
     * {@code buildApiDocQualityGate}가 기본값 1.0으로 읽어(해당 코드의 asDouble 기본값)
     * 합성/예제 대상을 걸러내는 P1-3 조건이 조용히 무력화된다.</p>
     */
    private void mergeGuideOnlyQualityFields(ObjectNode card, ApiDocGuideSupport.GuideView guide) {
        JsonNode qualityNode = card.path("guideQuality");
        if (!qualityNode.isObject()) {
            return;
        }
        ObjectNode quality = (ObjectNode) qualityNode;
        double targetSuitability = guide.quality().targetSuitabilityScore();
        quality.put("targetSuitabilityScore", targetSuitability);
        // slotEvidence 본문은 guide 값을 쓰므로 신뢰도 라벨도 같은 출처로 맞춘다.
        quality.put("slotEvidenceConfidence", guide.quality().slotEvidenceConfidence());
        // P1-3: meetsThreshold = actionability 충족 AND targetSuitability 충족(합성/예제 제외)
        quality.put("meetsThreshold",
                quality.path("actionabilityScore").asInt(0) >= ACTIONABILITY_THRESHOLD
                        && targetSuitability >= 1.0);
    }

    private void attachMethodGuideBundle(
            ObjectNode card,
            ApiDocGuideSupport.GuideView guide,
            JsonNode scenarioStep
    ) {
        String beforeCall = firstNonBlank(scenarioStep.path("precondition").asText(""), guide.slots().beforeCall());
        String doCall = firstNonBlank(
                scenarioStep.path("action").asText(""),
                scenarioStep.path("description").asText(""),
                guide.slots().doCall()
        );
        String successCheck = firstNonBlank(scenarioStep.path("successSignal").asText(""), guide.slots().successCheck());
        String failureSymptom = firstNonBlank(scenarioStep.path("failureSignal").asText(""), guide.slots().failureSymptom());
        String nextAction = firstNonBlank(
                scenarioStep.path("userAction").asText(""),
                scenarioStep.path("evidenceInterpretation").asText(""),
                guide.slots().nextAction()
        );
        String evidenceAnchor = firstNonBlank(evidenceAnchorFromLinks(scenarioStep.path("evidenceLinks")), guide.evidenceAnchor());
        attachGuideBundle(card, beforeCall, doCall, successCheck, failureSymptom, nextAction, evidenceAnchor);
    }

    private void attachUsageScenario(ObjectNode card, JsonNode scenarioStep) {
        if (card == null) {
            return;
        }
        if (scenarioStep == null || scenarioStep.isMissingNode() || scenarioStep.isNull()) {
            card.put("llmEnhanced", false);
            return;
        }
        ObjectNode usageScenario = card.putObject("usageScenario");
        copyTextFields(scenarioStep, usageScenario, List.of(
                "scenarioId",
                "scenarioTitle",
                "scenarioIntent",
                "description",
                "precondition",
                "action",
                "successSignal",
                "failureSignal",
                "userAction",
                "dataHandled",
                "evidenceInterpretation",
                "confidenceReason"
        ));
        usageScenario.put("stepNo", scenarioStep.path("stepNo").asInt(0));
        if (scenarioStep.path("confidence").isNumber()) {
            usageScenario.put("confidence", scenarioStep.path("confidence").asDouble());
        }
        if (scenarioStep.path("evidenceLinks").isArray()) {
            usageScenario.set("evidenceLinks", scenarioStep.path("evidenceLinks").deepCopy());
        }
        card.put("llmEnhanced", true);
    }

    private Map<String, JsonNode> indexScenarioStepByMethod(JsonNode scenarios) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (scenarios == null || !scenarios.isArray()) {
            return out;
        }
        for (JsonNode scenario : scenarios) {
            JsonNode steps = scenario.path("steps");
            if (!steps.isArray()) {
                continue;
            }
            for (JsonNode step : steps) {
                String methodFqn = step.path("methodFqn").asText("");
                if (methodFqn.isBlank() || out.containsKey(methodFqn)) {
                    continue;
                }
                ObjectNode indexed = step.deepCopy();
                putIfText(indexed, "scenarioId", scenario.path("scenarioId").asText(""));
                putIfText(indexed, "scenarioTitle", scenario.path("title").asText(""));
                putIfText(indexed, "scenarioIntent", scenario.path("intent").asText(""));
                out.put(methodFqn, indexed);
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
            if (method.has("apiFlowRef")) entry.set("apiFlowRef", method.path("apiFlowRef").deepCopy());
            if (method.has("flowTraceSummary")) entry.put("flowTraceSummary", method.path("flowTraceSummary").asText(""));
            ArrayNode relatedScenarios = entry.putArray("relatedScenarios");
            relatedScenarios.add("SCN-001");
        }
        return out;
    }

    /**
     * API 카드의 실전 가이드 품질 점수를 집계한다.
     * 메서드 대상이므로 P1-3 합성/예제 판정을 적용한다.
     */
    public ObjectNode buildApiDocQualityGate(JsonNode coreMethods) {
        return buildApiDocQualityGate(coreMethods, true);
    }

    /**
     * 가이드 품질 점수를 집계한다.
     *
     * @param applyTargetSuitability P1-3 합성/예제 판정을 적용할지.
     *        메서드 카드는 true. rules/cautions는 "메서드 대상"이 아니므로 false다 —
     *        규칙에 합성 메서드 판정을 적용하는 것 자체가 의미 왜곡이고,
     *        false로 두면 그 사실이 {@code targetSuitabilityApplied}로 산출물에 남는다.
     */
    public ObjectNode buildApiDocQualityGate(JsonNode coreMethods, boolean applyTargetSuitability) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("threshold", ACTIONABILITY_THRESHOLD);
        out.put("metric", "actionabilityScore");

        if (!coreMethods.isArray() || coreMethods.isEmpty()) {
            out.put("itemCount", 0);
            out.put("methodCount", 0);
            out.put("averageActionabilityScore", 0.0d);
            out.put("minActionabilityScore", 0);
            out.put("belowThresholdCount", 0);
            out.put("slotCoverageAvg", 0.0d);
            out.put("evidenceCoverageAvg", 0.0d);
            out.put("forbiddenPhraseRateAvg", 0.0d);
            out.put("repetitionRateAvg", 0.0d);
            out.put("targetSuitabilityApplied", applyTargetSuitability);
            out.put("targetSuitabilityMeasuredCount", 0);
            out.put("targetSuitabilityMissingCount", 0);
            out.put("targetSuitabilityAvg", 0.0d);
            out.put("narrativeDiversityAvg", 0.0d);
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
        double targetSuitabilitySum = 0.0d;
        double narrativeDiversitySum = 0.0d;
        int suitabilityMeasuredCount = 0;
        int suitabilityMissing = 0;

        for (int i = 0; i < coreMethods.size(); i++) {
            JsonNode method = coreMethods.get(i);
            JsonNode quality = method.path("guideQuality");
            int score = quality.path("actionabilityScore").asInt(method.path("actionabilityScore").asInt(0));
            double slotCoverage = quality.path("slotCoverage").asDouble(0.0d);
            double evidenceCoverage = quality.path("evidenceCoverage").asDouble(0.0d);
            double forbiddenRate = quality.path("forbiddenPhraseRate").asDouble(0.0d);
            double repetitionRate = quality.path("repetitionRate").asDouble(0.0d);
            double narrativeDiversity = 1.0d - repetitionRate;

            // 부재를 1.0으로 읽지 않는다. 예전에는 asDouble(1.0d)였는데,
            // targetSuitabilityScore를 생산하지 않는 경로(attachGuideBundle)의 결과가
            // 전부 "적합"으로 간주되어 P1-3 필터가 조용히 항상 통과했다.
            boolean suitabilityMeasured = quality.path("targetSuitabilityScore").isNumber();
            double targetSuitability = suitabilityMeasured
                    ? quality.path("targetSuitabilityScore").asDouble()
                    : 0.0d;

            count++;
            totalScore += score;
            minScore = Math.min(minScore, score);
            boolean fillerFree = forbiddenRate == 0.0d;
            boolean suitabilityOk = !applyTargetSuitability
                    || (suitabilityMeasured && targetSuitability >= 1.0d);
            if (score < ACTIONABILITY_THRESHOLD || !fillerFree || !suitabilityOk) {
                belowThreshold++;
            }
            if (applyTargetSuitability && !suitabilityMeasured) {
                suitabilityMissing++;
            }
            slotCoverageSum += slotCoverage;
            evidenceCoverageSum += evidenceCoverage;
            forbiddenRateSum += forbiddenRate;
            repetitionRateSum += repetitionRate;
            if (suitabilityMeasured) {
                targetSuitabilitySum += targetSuitability;
                suitabilityMeasuredCount++;
            }
            narrativeDiversitySum += narrativeDiversity;
        }

        out.put("itemCount", count);
        // methodCount는 하위호환용으로 남긴다. refined_rules에서는 실제로
        // rules + cautions 개수라 이름이 오독을 부른다. itemCount를 쓴다.
        out.put("methodCount", count);
        out.put("averageActionabilityScore", round2((double) totalScore / count));
        out.put("minActionabilityScore", minScore == Integer.MAX_VALUE ? 0 : minScore);
        out.put("belowThresholdCount", belowThreshold);
        out.put("slotCoverageAvg", round2(slotCoverageSum / count));
        out.put("evidenceCoverageAvg", round2(evidenceCoverageSum / count));
        out.put("forbiddenPhraseRateAvg", round2(forbiddenRateSum / count));
        out.put("repetitionRateAvg", round2(repetitionRateSum / count));
        out.put("targetSuitabilityApplied", applyTargetSuitability);
        out.put("targetSuitabilityMeasuredCount", suitabilityMeasuredCount);
        out.put("targetSuitabilityMissingCount", suitabilityMissing);
        // 측정된 항목만으로 평균을 낸다. 미측정을 1.0으로 셔서 더하면
        // 측정하지 않은 것이 평균을 올리는 이상한 지표가 된다.
        out.put("targetSuitabilityAvg",
                suitabilityMeasuredCount == 0 ? 0.0d : round2(targetSuitabilitySum / suitabilityMeasuredCount));
        out.put("narrativeDiversityAvg", round2(narrativeDiversitySum / count));
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
     * 시나리오 서술의 채움률을 집계한다.
     *
     * <p>STEP②는 5개 산출물 중 유일하게 품질 게이트가 없었다. 호출 분해로 서술 칸 채움을
     * 36/88에서 85/88로 올렸는데 그 값이 어디에도 기록되지 않아, 개선을 로그로만 확인하고
     * 산출물로는 증명할 수 없었다.</p>
     *
     * <p><b>guideSlots를 세면 안 된다.</b> {@code attachStepGuideBundle}의 폴백 체인
     * ({@code precondition→description}, {@code userAction→evidenceInterpretation})이 빈칸을
     * 가려서, 서술이 36/88이던 실행에서도 step의 {@code slotCoverage}는 1.0이었다.
     * 그래서 {@link #STEP_RICH_TEXT_FIELDS}의 원본 필드를 직접 센다.</p>
     *
     * <p>채운 칸 수와 별개로 {@code fillerFieldCount}를 함께 낸다. 채운 것과 제대로 채운 것은
     * 다르다 — 실측에서 85칸 중 1칸이 채움말이었다. 채움 수만 세면 모델이 채움말로 88칸을
     * 메웠을 때 100%로 보이고, 그건 지금 고치고 있는 바로 그 실패를 새 게이트에 다시 만드는 것이다.</p>
     */
    public ObjectNode buildScenarioSpecsQualityGate(JsonNode scenarios, JsonNode overview) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("threshold", SCENARIO_NARRATIVE_THRESHOLD);
        out.put("metric", "narrativeFieldCoverage");

        // description + STEP_RICH_TEXT_FIELDS 8개 = 9필드. 전부 PROMPT_SCENARIO_ONE의
        // 출력 스키마에 있는 필드다. 수기 집계 때는 confidenceReason을 빼고 8필드로 셌기 때문에
        // 예전 보고값(36/88, 85/88)과 분모가 다르다. 비율은 같은 결론을 준다(36% vs 92%).
        List<String> fields = new ArrayList<>();
        fields.add("description");
        fields.addAll(STEP_RICH_TEXT_FIELDS);

        Map<String, Integer> filledByField = new LinkedHashMap<>();
        for (String field : fields) {
            filledByField.put(field, 0);
        }

        int scenarioCount = 0;
        int stepCount = 0;
        int filled = 0;
        int fillerCount = 0;

        if (scenarios != null && scenarios.isArray()) {
            for (JsonNode scenario : scenarios) {
                scenarioCount++;
                for (JsonNode step : scenario.path("steps")) {
                    stepCount++;
                    for (String field : fields) {
                        String value = step.path(field).asText("");
                        if (safeText(value).isBlank()) {
                            continue;
                        }
                        filled++;
                        filledByField.merge(field, 1, Integer::sum);
                        if (ApiDocGuideSupport.isFiller(value)) {
                            fillerCount++;
                        }
                    }
                }
            }
        }

        int total = stepCount * fields.size();
        double coverage = total == 0 ? 0.0d : (double) filled / total;

        int overviewFilled = 0;
        if (overview != null && overview.isObject()) {
            for (JsonNode value : overview) {
                if (!safeText(value.asText("")).isBlank()) {
                    overviewFilled++;
                }
            }
        }

        out.put("scenarioCount", scenarioCount);
        out.put("stepCount", stepCount);
        out.put("narrativeFieldTotal", total);
        out.put("narrativeFieldFilled", filled);
        out.put("narrativeFieldCoverage", round2(coverage));
        out.put("fillerFieldCount", fillerCount);
        out.put("overviewFieldFilled", overviewFilled);

        ObjectNode byField = out.putObject("fieldCoverageByName");
        for (Map.Entry<String, Integer> entry : filledByField.entrySet()) {
            byField.put(entry.getKey(), entry.getValue());
        }

        // 채움말이 섞이면 채움률과 무관하게 통과시키지 않는다.
        out.put("meetsThreshold", coverage >= SCENARIO_NARRATIVE_THRESHOLD && fillerCount == 0);
        return out;
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
        // rules/cautions는 메서드 대상이 아니므로 합성/예제 판정을 적용하지 않는다.
        ObjectNode gate = buildApiDocQualityGate(merged, false);
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
     * api_flow traces가 존재하면 진입점 호출 경로 요약을 컨텍스트에 추가한다.
     */
    public String buildScenarioContext(
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("overviewSeed", structure.path("overviewSeed"));
        // scenarioSeed가 이 단계의 출력 골격이다. 모델은 여기에 서술만 채운다.
        context.set("scenarioSeed", structure.path("scenarioSeed"));
        context.set("coreClassSeed", takeFirst(structure.path("coreClassSeed"), 8));
        context.set("coreMethodSeed", takeFirst(structure.path("coreMethodSeed"), 14));
        context.set("methodFlowSeed", takeFirst(structure.path("methodFlowSeed"), 6));
        context.set("cautions", takeFirst(refinedRules.path("cautions"), 8));
        context.set("evidence", toEvidenceNode(evidence, 12));

        // api_flow 보강: 진입점별 호출 경로 요약 (상위 10개)
        JsonNode apiFlowTraces = structure.path("apiFlowTraces");
        if (apiFlowTraces.isArray() && !apiFlowTraces.isEmpty()) {
            context.set("apiFlowSummary", buildApiFlowSummary(apiFlowTraces, 10));
        }

        return toJson(context);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Phase 4-A: super-cluster 기반 서브시스템 문서 / 모듈 라벨
    // ──────────────────────────────────────────────────────────────────────

    /**
     * super-cluster 단위로 서브시스템 문서를 생성한다.
     * 각 super-cluster가 요약 단위(모듈 수준)가 되고, level-1 memberSubsystemIds는 근거로 첨부된다.
     */
    public ArrayNode buildSuperClusterSubsystemDocs(
            JsonNode superSubsystems,
            JsonNode coreClasses,
            JsonNode scenarioSpecs,
            JsonNode refinedRules
    ) {
        List<String> scenarioIds = collectIds(scenarioSpecs.path("scenarios"), "scenarioId");
        List<String> ruleIds = collectIds(refinedRules.path("rules"), "ruleId");

        // coreClass fqn → super-cluster displayName 매핑
        Map<String, String> packageToSuperLabel = buildPackageToSuperLabel(superSubsystems);

        ArrayNode out = objectMapper.createArrayNode();
        if (!superSubsystems.isArray()) return out;

        for (int i = 0; i < superSubsystems.size(); i++) {
            JsonNode sup = superSubsystems.get(i);
            String supId = sup.path("superSubsystemId").asText(String.format("sup_%03d", i + 1));
            String displayName = firstNonBlank(
                    sup.path("displayName").asText(""),
                    sup.path("canonicalKey").asText("module-" + (i + 1))
            );

            ObjectNode item = out.addObject();
            item.put("subsystemId", supId);
            item.put("label", displayName);
            item.put("description", "모듈 수준 서브시스템: " + displayName);
            item.put("layer", "module");
            item.put("moduleDisplayName", displayName);
            item.put("canonicalKey", sup.path("canonicalKey").asText(""));

            // level-1 근거 (memberSubsystemIds)
            JsonNode memberIds = sup.path("memberSubsystemIds");
            ArrayNode memberSubsystems = item.putArray("memberSubsystems");
            if (memberIds.isArray()) {
                for (JsonNode id : memberIds) {
                    memberSubsystems.add(id.asText(""));
                }
            }

            // moduleAffinity 보존
            item.set("moduleAffinity", sup.path("moduleAffinity").deepCopy());

            // topSymbols: memberSymbolIds 앞 5개
            ArrayNode topSymbols = item.putArray("topSymbols");
            JsonNode memberSymbols = sup.path("memberSymbolIds");
            if (memberSymbols.isArray()) {
                for (int j = 0; j < memberSymbols.size() && j < 5; j++) {
                    topSymbols.add(memberSymbols.get(j).asText(""));
                }
            }

            // relatedScenarios (전체 시나리오를 공유, 최대 3개)
            ArrayNode relatedScenarios = item.putArray("relatedScenarios");
            for (int j = 0; j < scenarioIds.size() && j < 3; j++) {
                relatedScenarios.add(scenarioIds.get(j));
            }

            // ruleIds (전체 규칙을 공유, 최대 4개)
            ArrayNode relatedRules = item.putArray("ruleIds");
            for (int j = 0; j < ruleIds.size() && j < 4; j++) {
                relatedRules.add(ruleIds.get(j));
            }

            item.put("memberCount", sup.path("memberCount").asInt(0));
        }
        return out;
    }

    /**
     * super-cluster 목록에서 module-grain 라벨 배열을 생성한다.
     * step ⑤ file_tree_docs의 보조 입력으로 사용한다.
     */
    public ArrayNode buildModuleLabels(JsonNode superSubsystems) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!superSubsystems.isArray()) return out;

        for (JsonNode sup : superSubsystems) {
            ObjectNode label = out.addObject();
            label.put("superSubsystemId", sup.path("superSubsystemId").asText(""));
            label.put("canonicalKey", sup.path("canonicalKey").asText(""));
            label.put("displayName", firstNonBlank(
                    sup.path("displayName").asText(""),
                    sup.path("canonicalKey").asText("")
            ));
            label.set("packageRoots", sup.path("packageRoots").deepCopy());
            label.put("memberCount", sup.path("memberCount").asInt(0));
        }
        return out;
    }

    private Map<String, String> buildPackageToSuperLabel(JsonNode superSubsystems) {
        Map<String, String> out = new HashMap<>();
        if (!superSubsystems.isArray()) return out;
        for (JsonNode sup : superSubsystems) {
            String displayName = firstNonBlank(sup.path("displayName").asText(""), sup.path("canonicalKey").asText(""));
            JsonNode roots = sup.path("packageRoots");
            if (roots.isArray()) {
                for (JsonNode root : roots) {
                    out.putIfAbsent(root.asText(""), displayName);
                }
            }
        }
        return out;
    }

    private List<String> collectIds(JsonNode array, String idField) {
        List<String> ids = new ArrayList<>();
        if (!array.isArray()) return ids;
        for (JsonNode item : array) {
            String id = item.path(idField).asText("");
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Phase 4-B: api_flow 보강 메서드
    // ──────────────────────────────────────────────────────────────────────

    /**
     * API flow traces에서 컨텍스트 요약을 생성한다 (step ② 시나리오 컨텍스트 보강).
     */
    public ArrayNode buildApiFlowSummary(JsonNode apiFlowTraces, int maxEntries) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!apiFlowTraces.isArray()) return out;

        for (int i = 0; i < apiFlowTraces.size() && out.size() < maxEntries; i++) {
            JsonNode trace = apiFlowTraces.get(i);
            ObjectNode summary = out.addObject();
            summary.put("entryPoint", firstNonBlank(
                    trace.path("entryName").asText(""),
                    trace.path("entryQualifiedName").asText("")
            ));
            summary.put("entryQualifiedName", trace.path("entryQualifiedName").asText(""));
            summary.put("exposure", trace.path("exposure").asText(""));
            summary.put("reachableCount", trace.path("reachableNodes").size());
            summary.put("maxDepth", trace.path("maxDepth").asInt(0));
            summary.put("truncated", trace.path("truncated").asBoolean(false));

            // 직접 호출되는 메서드 이름 (depth=1, 최대 5개)
            ArrayNode directCallees = summary.putArray("directCallees");
            JsonNode nodes = trace.path("reachableNodes");
            if (nodes.isArray()) {
                for (JsonNode node : nodes) {
                    if (node.path("bfsDepth").asInt(0) == 1 && directCallees.size() < 5) {
                        directCallees.add(firstNonBlank(
                                node.path("name").asText(""),
                                node.path("qualifiedName").asText("")
                        ));
                    }
                }
            }
        }
        return out;
    }

    /**
     * coreMethods에 flow trace 요약을 연결한다 (step ④ API docs 보강).
     *
     * <p>trace의 entryQualifiedName은 TYPE FQN("org.foo.Bar")이고,
     * coreMethods의 fqn은 METHOD FQN("org.foo.Bar.method")이다.
     * 단위가 달라 직접 매칭이 불가능하므로 method.classFqn → TYPE FQN으로 변환하여 연결한다.
     * 같은 타입에 속한 여러 메서드는 동일 flow trace 요약을 공유한다.
     */
    public void enrichCoreMethodsWithFlowTraces(ArrayNode coreMethods, JsonNode apiFlowTraces) {
        if (!apiFlowTraces.isArray() || apiFlowTraces.isEmpty()) return;

        // TYPE FQN → trace 인덱스 구성
        // entryQualifiedName 형식: "type:org.foo.Bar"
        // normalizeEntryQualifiedName 후: "org.foo.Bar" (TYPE FQN)
        Map<String, JsonNode> traceByTypeFqn = new HashMap<>();
        for (JsonNode trace : apiFlowTraces) {
            String qn = trace.path("entryQualifiedName").asText("");
            if (qn.isBlank()) continue;
            String typeFqn = normalizeEntryQualifiedName(qn);
            if (!typeFqn.isBlank()) traceByTypeFqn.put(typeFqn, trace);
        }

        int matched = 0;
        int unmatched = 0;
        for (JsonNode method : coreMethods) {
            if (!method.isObject()) continue;
            // classFqn이 TYPE FQN과 직접 매칭됨. 없으면 method fqn에서 추출
            String classFqn = firstNonBlank(
                    method.path("classFqn").asText(""),
                    ownerFromMethodFqn(method.path("fqn").asText(""))
            );
            JsonNode trace = traceByTypeFqn.get(classFqn);
            if (trace == null) {
                unmatched++;
                continue;
            }
            matched++;

            ObjectNode methodObj = (ObjectNode) method;
            int reachableCount = trace.path("reachableNodes").size();
            int maxDepth = trace.path("maxDepth").asInt(0);
            boolean truncated = trace.path("truncated").asBoolean(false);

            ObjectNode flowRef = methodObj.putObject("apiFlowRef");
            flowRef.put("reachableCount", reachableCount);
            flowRef.put("maxDepth", maxDepth);
            flowRef.put("truncated", truncated);

            // directCallees (bfsDepth=1, 최대 5개)
            List<String> directCallees = new java.util.ArrayList<>();
            JsonNode nodes = trace.path("reachableNodes");
            if (nodes.isArray()) {
                for (JsonNode node : nodes) {
                    if (node.path("bfsDepth").asInt(0) == 1 && directCallees.size() < 5) {
                        String name = firstNonBlank(
                                node.path("name").asText(""),
                                node.path("qualifiedName").asText(""));
                        if (!name.isBlank()) directCallees.add(name);
                    }
                }
            }

            // flowTraceSummary: "진입점이 최대 N단계, M개 노드 도달. 직접 호출: a, b, c"
            StringBuilder sb = new StringBuilder();
            sb.append("진입점이 최대 ").append(maxDepth).append("단계 깊이로 ")
              .append(reachableCount).append("개 노드에 도달");
            if (truncated) sb.append(" (경로 일부 생략)");
            sb.append(".");
            if (!directCallees.isEmpty()) {
                sb.append(" 직접 호출: ").append(String.join(", ", directCallees)).append(".");
            }
            methodObj.put("flowTraceSummary", sb.toString());
        }
        log.info("[LLM] flowTrace enrich: coreMethods={}, traces={}, matched={}, unmatched={}",
                coreMethods.size(), apiFlowTraces.size(), matched, unmatched);
    }

    /**
     * API_FLOW_TRACE_JSON entryQualifiedName → TYPE FQN으로 정규화.
     * "type:org.foo.Bar" → "org.foo.Bar"
     * "method:org.foo.Bar#doSomething(params)" → "org.foo.Bar.doSomething" (레거시 대비)
     */
    private static String normalizeEntryQualifiedName(String qn) {
        if (qn == null || qn.isBlank()) return "";
        // kind prefix 제거 ("method:", "type:", "ctor:" 등)
        int colonIdx = qn.indexOf(':');
        String stripped = colonIdx >= 0 ? qn.substring(colonIdx + 1) : qn;
        // 파라미터 제거 ("(..." 이후)
        int parenIdx = stripped.indexOf('(');
        if (parenIdx >= 0) stripped = stripped.substring(0, parenIdx);
        // '#' → '.' (class#method → class.method)
        return stripped.replace('#', '.');
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

    private void writeSlotEvidence(ObjectNode node, ApiDocGuideSupport.SlotEvidence evidence) {
        if (node == null || evidence == null) {
            return;
        }
        putIfText(node, "beforeCall", evidence.beforeCall());
        putIfText(node, "doCall", evidence.doCall());
        putIfText(node, "successCheck", evidence.successCheck());
        putIfText(node, "failureSymptom", evidence.failureSymptom());
        putIfText(node, "nextAction", evidence.nextAction());
        node.put("slotEvidenceConfidence", firstNonBlank(evidence.confidence(), "method_level"));
    }

    private void copyTextFields(JsonNode source, ObjectNode target, List<String> fieldNames) {
        if (source == null || target == null || fieldNames == null) {
            return;
        }
        for (String fieldName : fieldNames) {
            putIfText(target, fieldName, source.path(fieldName).asText(""));
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

    /**
     * 문장 공백만 정리한다. 값이 없으면 빈 문자열을 그대로 돌려준다.
     *
     * <p>예전에는 빈 슬롯을 "핵심 동작 수행"으로 채웠는데 두 가지 문제가 있었다.
     * 첫째, 이 문구는 프롬프트가 금지한 표현이라({@code GUIDE_FORBIDDEN_PHRASES})
     * 코드가 스스로 주입하고 {@code computeForbiddenPhraseRate}가 그걸 다시 감점했다.
     * 둘째, 빈 슬롯이 사라지면서 {@code computeSlotCoverage}가 항상 1.0을 반환해
     * 점수 가중치의 45%가 상수가 됐다(실측: 두 차례 실행 20개 step 전부 1.0).
     * 없는 값은 없는 채로 두고 지표가 그것을 드러내게 한다.</p>
     */
    private String normalizeSentence(String text) {
        return safeText(text).replaceAll("\\s+", " ").trim();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }
}


