package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PER_SCENARIO 모드에서 <b>호출 하나짜리 컨텍스트</b>를 만든다.
 *
 * <p>이 클래스가 존재하는 이유는 하나다. step ②를 시나리오 단위로 쪼개면 프롬프트가 호출 수만큼
 * 재처리되는데, 컨텍스트를 그대로 둔 채 쪼개면 그 비용이 전부 순증한다. 8차 baseline 실측으로
 * step ② 프롬프트는 12,489토큰 / 14.2분이었고, 5회로 나누면 62,445토큰 / 약 71분이 된다.
 * 시나리오 한 장에 필요 없는 시드를 걷어내야 비로소 분해가 성립한다.</p>
 *
 * <p>걷어낼 수 있는 근거는 골격 자체에 있다. 골격 step은 이미 methodFqn / classFqn /
 * filePath / startLine을 들고 있으므로, 그 값으로 coreMethodSeed·coreClassSeed·evidence·cautions를
 * 이 시나리오에 걸리는 것만 남기면 된다.</p>
 *
 * @see LlmServiceBuildSupport#buildScenarioContext 단일 호출(SINGLE) 모드의 컨텍스트
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmScenarioContextSupport {

    /** 시나리오 하나에 실어 줄 근거 스니펫 상한. 골격 step이 최대 6개라 그보다 조금 넉넉하게 잡는다. */
    private static final int MAX_EVIDENCE_PER_SCENARIO = 8;

    /** 시나리오 하나에 실어 줄 주의사항 상한. 관련 없는 주의사항은 서술을 흐린다. */
    private static final int MAX_CAUTIONS_PER_SCENARIO = 4;

    private final ObjectMapper objectMapper;

    /**
     * overview 호출용 컨텍스트. 시나리오 골격은 싣지 않는다.
     * 개요는 프로젝트 수준 시드만 있으면 되고, 골격을 실으면 모델이 시나리오를 쓰기 시작한다.
     */
    public String buildOverviewContext(JsonNode structure, JsonNode refinedRules) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("overviewSeed", structure.path("overviewSeed"));
        context.set("coreClassSeed", takeFirst(structure.path("coreClassSeed"), 8));
        context.set("cautions", takeFirst(refinedRules.path("cautions"), 6));
        return toJson(context);
    }

    /**
     * 시나리오 한 장짜리 컨텍스트.
     *
     * <p>{@code excludedByOtherScenarios}를 함께 싣는다. 골격 생성은 같은 메서드를 두 시나리오에
     * 넣지 않으므로(usedFqn 중복 배제), 격리된 호출에서는 "이 타입의 그 메서드가 왜 여기 없는지"를
     * 알 방법이 없다. 그대로 두면 모델이 "앞서 생성한 객체를 이어서" 같은 근거 없는 서술로
     * 빈자리를 메우게 된다. 제목만 알려주는 것보다 싸고 정확하다.</p>
     */
    public String buildOneScenarioContext(
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence,
            JsonNode scenarioSeed,
            int scenarioIndex
    ) {
        JsonNode target = scenarioSeed.get(scenarioIndex);

        Set<String> methodFqns = new LinkedHashSet<>();
        Set<String> classFqns = new LinkedHashSet<>();
        Set<String> filePaths = new LinkedHashSet<>();
        for (JsonNode step : target.path("steps")) {
            addIfText(methodFqns, step.path("methodFqn").asText(""));
            addIfText(classFqns, step.path("classFqn").asText(""));
            addIfText(filePaths, step.path("filePath").asText(""));
        }

        ObjectNode context = objectMapper.createObjectNode();
        context.set("overviewSeed", structure.path("overviewSeed"));
        context.set("scenario", target);
        context.set("coreClassSeed", filterByFqn(structure.path("coreClassSeed"), classFqns, "fqn"));
        context.set("coreMethodSeed", filterByFqn(structure.path("coreMethodSeed"), methodFqns, "fqn"));
        context.set("cautions", filterCautions(refinedRules.path("cautions"), classFqns, methodFqns));
        context.set("evidence", filterEvidence(evidence, filePaths));
        context.set("excludedByOtherScenarios", buildExclusions(scenarioSeed, scenarioIndex, classFqns));

        return toJson(context);
    }

    /**
     * 이 시나리오의 타입에 속하지만 다른 시나리오가 가져간 메서드 목록.
     *
     * <p>같은 클래스인 것만 싣는다. 다른 클래스의 메서드는 이 시나리오에서 원래 다룰 일이 없어
     * 알려 줘 봐야 컨텍스트만 늘어난다.</p>
     */
    private ArrayNode buildExclusions(JsonNode scenarioSeed, int scenarioIndex, Set<String> classFqns) {
        ArrayNode out = objectMapper.createArrayNode();
        if (classFqns.isEmpty()) {
            return out;
        }
        for (int i = 0; i < scenarioSeed.size(); i++) {
            if (i == scenarioIndex) {
                continue;
            }
            JsonNode other = scenarioSeed.get(i);
            for (JsonNode step : other.path("steps")) {
                String classFqn = step.path("classFqn").asText("");
                String methodFqn = step.path("methodFqn").asText("");
                if (methodFqn.isBlank() || !classFqns.contains(classFqn)) {
                    continue;
                }
                ObjectNode item = out.addObject();
                item.put("methodFqn", methodFqn);
                item.put("coveredByScenarioId", other.path("scenarioId").asText(""));
            }
        }
        return out;
    }

    /** 지정한 키 값이 허용 집합에 있는 항목만 남긴다. */
    private ArrayNode filterByFqn(JsonNode source, Set<String> allowed, String key) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!source.isArray() || allowed.isEmpty()) {
            return out;
        }
        for (JsonNode item : source) {
            if (allowed.contains(item.path(key).asText(""))) {
                out.add(item);
            }
        }
        return out;
    }

    /** relatedClass/relatedMethod가 이 시나리오에 걸리는 주의사항만 남긴다. */
    private ArrayNode filterCautions(JsonNode cautions, Set<String> classFqns, Set<String> methodFqns) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!cautions.isArray()) {
            return out;
        }
        for (JsonNode caution : cautions) {
            if (out.size() >= MAX_CAUTIONS_PER_SCENARIO) {
                break;
            }
            String relatedClass = caution.path("relatedClass").asText("");
            String relatedMethod = caution.path("relatedMethod").asText("");
            if (classFqns.contains(relatedClass) || methodFqns.contains(relatedMethod)) {
                out.add(caution);
            }
        }
        return out;
    }

    /** 이 시나리오 step이 가리키는 파일의 근거만 남긴다. */
    private JsonNode filterEvidence(List<LlmRequest.EvidenceSnippet> evidence, Set<String> filePaths) {
        ArrayNode out = objectMapper.createArrayNode();
        if (evidence == null || evidence.isEmpty() || filePaths.isEmpty()) {
            return out;
        }
        List<LlmRequest.EvidenceSnippet> matched = new ArrayList<>();
        for (LlmRequest.EvidenceSnippet snippet : evidence) {
            if (snippet != null && filePaths.contains(snippet.getFilePath())) {
                matched.add(snippet);
            }
        }
        for (int i = 0; i < matched.size() && i < MAX_EVIDENCE_PER_SCENARIO; i++) {
            LlmRequest.EvidenceSnippet snippet = matched.get(i);
            ObjectNode item = out.addObject();
            item.put("evidenceId", snippet.getEvidenceId());
            item.put("filePath", snippet.getFilePath());
            item.put("startLine", snippet.getStartLine());
            item.put("endLine", snippet.getEndLine());
            item.put("snippet", snippet.getSnippet());
        }
        return out;
    }

    private ArrayNode takeFirst(JsonNode arrayNode, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!arrayNode.isArray()) {
            return out;
        }
        for (int i = 0; i < arrayNode.size() && i < limit; i++) {
            out.add(arrayNode.get(i));
        }
        return out;
    }

    private void addIfText(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
