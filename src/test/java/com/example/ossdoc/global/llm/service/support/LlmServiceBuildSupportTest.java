package com.example.ossdoc.global.llm.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmServiceBuildSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmServiceBuildSupport support = new LlmServiceBuildSupport(objectMapper);

    @Test
    void normalizeScenarioSpecs_preservesRichStepFields() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "overview": {
                    "project": "ossdoc",
                    "purpose": "Explain code from graph evidence",
                    "architectureSummary": "Worker builds graph facts before LLM refinement",
                    "dataFlow": "facts -> graph -> scenario"
                  },
                  "scenarios": [{
                    "scenarioId": "SCN-001",
                    "title": "Analyze repository",
                    "intent": "Run analysis from a repository URL",
                    "whyThisMatters": "Users need evidence-backed onboarding",
                    "steps": [{
                      "stepNo": 1,
                      "description": "Check the repository URL and run the analysis request.",
                      "precondition": "A repository URL is available.",
                      "action": "Call RepoRunService.createRun with the repository URL.",
                      "successSignal": "The run status changes to queued.",
                      "failureSignal": "Invalid URL validation rejects the request.",
                      "userAction": "Fix the URL and retry.",
                      "dataHandled": "repoUrl",
                      "evidenceInterpretation": "The method creates the run command from the request DTO.",
                      "classFqn": "com.example.ossdoc.domain.run.service.RepoRunService",
                      "methodFqn": "com.example.ossdoc.domain.run.service.RepoRunService.createRun",
                      "confidence": 0.91,
                      "evidenceLinks": [{"evidenceId": 7, "filePath": "src/main/java/RepoRunService.java", "lines": "10-30"}]
                    }]
                  }],
                  "methodFlow": []
                }
                """);

        JsonNode normalized = support.normalizeScenarioSpecs(raw, structureWithMethodSeed());

        JsonNode overview = normalized.path("overview");
        JsonNode step = normalized.path("scenarios").get(0).path("steps").get(0);

        assertThat(overview.path("architectureSummary").asText()).contains("Worker builds graph facts");
        assertThat(step.path("action").asText()).contains("createRun");
        assertThat(step.path("successSignal").asText()).contains("queued");
        assertThat(step.path("evidenceInterpretation").asText()).contains("request DTO");
        assertThat(step.path("guideSlots").path("doCall").asText()).contains("createRun");
        assertThat(step.path("guideNarrative").asText()).contains("Fix the URL");
    }

    @Test
    void buildCoreMethodCards_usesScenarioStepNarrative() {
        ArrayNode scenarios = objectMapper.createArrayNode();
        scenarios.addObject()
                .put("scenarioId", "SCN-001")
                .put("title", "Analyze repository")
                .put("intent", "Run analysis from a repository URL")
                .set("steps", scenarioSteps());

        ArrayNode cards = support.buildCoreMethodCards(
                structureWithMethodSeed().path("coreMethodSeed"),
                objectMapper.createArrayNode(),
                objectMapper.createArrayNode(),
                scenarios
        );

        JsonNode card = cards.get(0);

        assertThat(card.path("llmEnhanced").asBoolean()).isTrue();
        assertThat(card.path("whatItDoes").asText()).contains("createRun");
        assertThat(card.path("guideSlots").path("failureSymptom").asText()).contains("Invalid URL");
        assertThat(card.path("usageScenario").path("evidenceInterpretation").asText()).contains("request DTO");
    }

    private ObjectNode structureWithMethodSeed() {
        ObjectNode structure = objectMapper.createObjectNode();
        structure.set("overviewSeed", objectMapper.createObjectNode().put("repoName", "ossdoc"));
        ArrayNode methodSeed = structure.putArray("coreMethodSeed");
        methodSeed.addObject()
                .put("fqn", "com.example.ossdoc.domain.run.service.RepoRunService.createRun")
                .put("classFqn", "com.example.ossdoc.domain.run.service.RepoRunService")
                .put("className", "RepoRunService")
                .put("methodName", "createRun")
                .put("summarySeed", "Creates a repository analysis run.")
                .put("signatureHint", "RepoRunCreateRequest -> RepoRunCreateResponse")
                .put("filePath", "src/main/java/RepoRunService.java")
                .put("startLine", 10)
                .put("endLine", 30)
                .put("importance", 100);
        structure.set("methodFlowSeed", objectMapper.createArrayNode());
        return structure;
    }

    private ArrayNode scenarioSteps() {
        ArrayNode steps = objectMapper.createArrayNode();
        steps.addObject()
                .put("stepNo", 1)
                .put("description", "Check the repository URL and run the analysis request.")
                .put("precondition", "A repository URL is available.")
                .put("action", "Call RepoRunService.createRun with the repository URL.")
                .put("successSignal", "The run status changes to queued.")
                .put("failureSignal", "Invalid URL validation rejects the request.")
                .put("userAction", "Fix the URL and retry.")
                .put("dataHandled", "repoUrl")
                .put("evidenceInterpretation", "The method creates the run command from the request DTO.")
                .put("classFqn", "com.example.ossdoc.domain.run.service.RepoRunService")
                .put("methodFqn", "com.example.ossdoc.domain.run.service.RepoRunService.createRun")
                .put("confidence", 0.91)
                .set("evidenceLinks", objectMapper.createArrayNode()
                        .addObject()
                        .put("evidenceId", 7)
                        .put("filePath", "src/main/java/RepoRunService.java")
                        .put("lines", "10-30"));
        return steps;
    }
}
