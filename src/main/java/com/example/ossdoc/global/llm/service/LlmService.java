package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.global.config.LlmConfig;
import com.example.ossdoc.global.llm.dto.json.LlmResult;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.dto.response.LlmResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestClient claudeRestClient;
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper;
    private final ArtifactService artifactService;
    private final RepoRunRepository repoRunRepository;

    // ── Artifact Paths ────────────────────────────────────────────────────────

    private static final String PATH_REFINED_RULES       = "llm/refined_rules.json";
    private static final String PATH_SCENARIO_SPECS      = "llm/scenario_specs.json";
    private static final String PATH_SUBSYSTEM_SUMMARIES = "llm/subsystem_summaries.json";
    private static final String PATH_API_DOCS            = "llm/api_docs.json";

    // ── System Prompts ────────────────────────────────────────────────────────

    private static final String PROMPT_REFINED_RULES = """
            You are a software architecture analyst specializing in code rule extraction.
            Analyze rule candidates from a static analysis engine and produce merged, named, classified rules.
            
            For each rule group:
            1. Merge duplicate or overlapping candidates into one canonical rule
            2. Assign a short kebab-case name (max 5 words)
            3. Classify as "defensive" (guard/validation/null-check/error-handling) or "domain" (business logic/invariant)
            4. Write a concise description (1-2 sentences)
            5. List supporting evidence IDs
            
            Respond with ONLY valid JSON. No markdown fences, no explanation.
            Schema: {"rules":[{"ruleId":"string","name":"string","classification":"defensive|domain",
            "description":"string","mergedFromGroups":["groupId"],"evidenceIds":[1],"confidence":0.0}]}
            """;

    private static final String PROMPT_SCENARIO_SPECS = """
            You are a software documentation expert specializing in scenario specification.
            Convert rule candidates and graph edges into structured scenario specifications.
            
            For each scenario:
            1. Write a concise title (Given-When-Then style, 1 sentence)
            2. Break into ordered steps (precondition → trigger → main flow → outcome)
            3. For EACH step, link back to evidence IDs that justify that step
            4. Identify the primary subsystem involved
            
            Respond with ONLY valid JSON. No markdown fences, no explanation.
            Schema: {"scenarios":[{"scenarioId":"string","title":"string","subsystem":"string",
            "steps":[{"stepNo":1,"description":"string",
            "evidenceLinks":[{"evidenceId":1,"filePath":"string","lines":"10-15"}]}]}]}
            """;

    private static final String PROMPT_SUBSYSTEM_SUMMARIES = """
            You are a software architect performing subsystem decomposition.
            Analyze symbols, edges, and module structure to identify and label subsystems.
            
            For each subsystem:
            1. Assign a short PascalCase label (max 3 words, e.g. "AuthGuard", "OrderDomain")
            2. Write a brief description (1 sentence, max 20 words)
            3. List top symbol FQNs belonging to this subsystem
            4. Indicate layer: "infrastructure", "domain", or "application"
            
            Respond with ONLY valid JSON. No markdown fences, no explanation.
            Schema: {"subsystems":[{"subsystemId":"string","label":"string","description":"string",
            "layer":"infrastructure|domain|application","topSymbols":["fqn1"],"ruleIds":["ruleId1"]}]}
            """;

    private static final String PROMPT_API_DOCS = """
            You are a technical writer generating API documentation from static analysis.
            Produce developer-facing documentation for each public API entry.
            
            For each public API:
            1. Write a clear summary (1 sentence)
            2. Document each parameter with name, type, and description
            3. Document the return type and what it represents
            4. Note any exceptions that may be thrown
            5. Link to relevant scenario IDs
            
            Respond with ONLY valid JSON. No markdown fences, no explanation.
            Schema: {"apiEntries":[{"fqn":"string","summary":"string",
            "parameters":[{"name":"string","type":"string","description":"string"}],
            "returns":{"type":"string","description":"string"},
            "throws":[{"type":"string","condition":"string"}],
            "relatedScenarios":["scenarioId1"],"subsystem":"string"}]}
            """;

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional
    public LlmResponse refine(LlmRequest request) {
        log.info("[LlmService] Starting refinement. runId={}", request.getRunId());

        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new LlmException(LlmErrorCode.RUN_NOT_FOUND));

        String baseContext = buildContext(request);

        // Step 1 - Refined Rules
        log.info("[LlmService] Step 1/4 - refined_rules");
        JsonNode refinedRules = callClaude(PROMPT_REFINED_RULES, baseContext);
        artifactService.saveJsonArtifact(run, ArtifactKind.LLM_REFINED_RULES,
                "1.0", PATH_REFINED_RULES, refinedRules);

        // Step 2 - Scenario Specs
        log.info("[LlmService] Step 2/4 - scenario_specs");
        String scenarioContext = baseContext
                + "\n\n--- Refined Rules ---\n" + toJson(refinedRules);
        JsonNode scenarioSpecs = callClaude(PROMPT_SCENARIO_SPECS, scenarioContext);
        artifactService.saveJsonArtifact(run, ArtifactKind.LLM_SCENARIO_SPECS,
                "1.0", PATH_SCENARIO_SPECS, scenarioSpecs);

        // Step 3 - Subsystem Summaries
        log.info("[LlmService] Step 3/4 - subsystem_summaries");
        String subsystemContext = baseContext
                + "\n\n--- Refined Rules ---\n" + toJson(refinedRules)
                + "\n\n--- Scenario Specs ---\n" + toJson(scenarioSpecs);
        JsonNode subsystemSummaries = callClaude(PROMPT_SUBSYSTEM_SUMMARIES, subsystemContext);
        artifactService.saveJsonArtifact(run, ArtifactKind.LLM_SUBSYSTEM_SUMMARIES,
                "1.0", PATH_SUBSYSTEM_SUMMARIES, subsystemSummaries);

        // Step 4 - API Docs
        log.info("[LlmService] Step 4/4 - api_docs");
        String apiContext = baseContext
                + "\n\n--- Subsystem Summaries ---\n" + toJson(subsystemSummaries)
                + "\n\n--- Scenario Specs ---\n" + toJson(scenarioSpecs);
        JsonNode apiDocs = callClaude(PROMPT_API_DOCS, apiContext);
        artifactService.saveJsonArtifact(run, ArtifactKind.LLM_API_DOCS,
                "1.0", PATH_API_DOCS, apiDocs);

        log.info("[LlmService] Refinement complete. runId={}", request.getRunId());

        LlmResult llmResult = LlmResult.builder()
                .runId(request.getRunId())
                .refinedRules(refinedRules)
                .scenarioSpecs(scenarioSpecs)
                .subsystemSummaries(subsystemSummaries)
                .apiDocs(apiDocs)
                .build();

        return new LlmResponse(request.getRunId(), llmResult);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    // RestClient로 /v1/messages 호출 후 parseResponse()
    private JsonNode callClaude(String systemPrompt, String userMessage) {
        String requestBody = buildRequestBody(systemPrompt, userMessage);

        String raw = claudeRestClient.post()
                .uri("/v1/messages")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return parseResponse(raw);
    }

    // Anthropic API 규격에 맞는 요청 JSON 생성 (system + messages)
    private String buildRequestBody(String systemPrompt, String userMessage) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", llmConfig.getModel());
            root.put("max_tokens", llmConfig.getMaxTokens());
            root.put("system", systemPrompt);

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);

            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmErrorCode.REQUEST_SERIALIZE_FAILED);
        }
    }

    // Claude 응답의 content[].text 블록 추출 후 JsonNode로 파싱
    private JsonNode parseResponse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);

            if (root.has("error")) {
                String msg = root.path("error").path("message").asText("unknown");
                log.error("[LlmService] Claude API error: {}", msg);
                throw new LlmException(LlmErrorCode.CLAUDE_API_ERROR);
            }

            String text = "";
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text = block.path("text").asText();
                    break;
                }
            }

            return objectMapper.readTree(stripFence(text.trim()));
        } catch (JsonProcessingException e) {
            log.error("[LlmService] Failed to parse Claude response: {}", raw);
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
    }

    // LLM이 응답을 ```json ```으로 감쌀 경우 제거
    private String stripFence(String text) {
        if (!text.startsWith("```")) return text;
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return text.substring(firstNewline + 1, lastFence).trim();
        }
        return text;
    }

    // 구조 엔진 출력 + Evidence 스니펫을 하나의 텍스트로 합침
    // structureEngineOutput + evidenceBundle을 Claude에 보낼 텍스트로 조합
    private String buildContext(LlmRequest request) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== Structure Engine Output ===\n");
        sb.append(toJson(request.getStructureEngineOutput()));
        sb.append("\n\n=== Evidence Bundle ===\n");

        for (LlmRequest.EvidenceSnippet ev : request.getEvidenceBundle()) {
            sb.append("# Evidence ").append(ev.getEvidenceId())
                    .append(" [").append(ev.getEvidenceType()).append("]\n");
            sb.append("File: ").append(ev.getFilePath())
                    .append(" (lines ").append(ev.getStartLine())
                    .append("-").append(ev.getEndLine()).append(")\n");
            sb.append(ev.getSnippet()).append("\n\n");
        }

        return sb.toString();
    }

    // JsonNode/Map 모두 처리 가능한 범용 직렬화
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}