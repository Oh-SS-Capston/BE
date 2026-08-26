package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.config.LlmGenerationProperties;
import com.example.ossdoc.global.llm.model.CoreMethodSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmInputAssemblerBuildSupportScenarioSeedTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmInputAssemblerBuildSupport newSupport(int maxScenarios, int maxSteps) {
        LlmGenerationProperties properties = new LlmGenerationProperties();
        properties.setMaxScenarios(maxScenarios);
        properties.setMaxStepsPerScenario(maxSteps);
        return new LlmInputAssemblerBuildSupport(objectMapper, properties);
    }

    @Test
    @DisplayName("api_map이 준 호출 순서는 대표 흐름 시나리오가 되고 순서를 유지한다")
    void realCallOrderBecomesFlowScenario() {
        JsonNode seed = newSupport(4, 8).buildScenarioSeed(realMethodFlow(), coreMethods());

        JsonNode flow = seed.get(0);
        assertThat(flow.path("title").asText()).isEqualTo("대표 호출 흐름");
        assertThat(stepFqns(flow)).containsExactly(
                "com.acme.Builder.required",
                "com.acme.Parser.parse"
        );
    }

    @Test
    @DisplayName("이름 키워드로 유도된 흐름(derived)은 대표 흐름으로 쓰지 않는다")
    void derivedFlowIsNotUsedAsScenario() {
        ArrayNode derived = (ArrayNode) realMethodFlow();
        for (JsonNode step : derived) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) step).put("derived", true);
        }

        JsonNode seed = newSupport(4, 8).buildScenarioSeed(derived, coreMethods());

        assertThat(seed).isNotEmpty();
        for (JsonNode scenario : seed) {
            assertThat(scenario.path("title").asText()).isNotEqualTo("대표 호출 흐름");
        }
    }

    @Test
    @DisplayName("단계를 2개도 못 내는 타입은 시나리오로 만들지 않는다")
    void skipsTypesThatCannotFillAScenario() {
        JsonNode seed = newSupport(6, 8).buildScenarioSeed(NullNode.getInstance(), coreMethods());

        List<String> titles = new ArrayList<>();
        for (JsonNode scenario : seed) {
            titles.add(scenario.path("title").asText());
            assertThat(scenario.path("steps").size()).isGreaterThanOrEqualTo(2);
        }
        // Lonely는 공개 메서드가 하나뿐이라 후보가 되지 못한다.
        assertThat(titles).containsExactly("Builder 사용 흐름", "Parser 사용 흐름");
    }

    @Test
    @DisplayName("Object 오버라이드는 단계에서 제외한다")
    void excludesObjectOverrides() {
        JsonNode seed = newSupport(6, 8).buildScenarioSeed(NullNode.getInstance(), coreMethods());

        List<String> all = new ArrayList<>();
        for (JsonNode scenario : seed) {
            all.addAll(stepFqns(scenario));
        }
        assertThat(all).noneMatch(fqn -> fqn.endsWith(".hashCode")
                || fqn.endsWith(".equals")
                || fqn.endsWith(".toString"));
        assertThat(all).contains("com.acme.Parser.getValue");
    }

    @Test
    @DisplayName("오버로드는 fqn이 같으므로 단계로 한 번만 쓴다")
    void collapsesOverloadsIntoOneStep() {
        JsonNode seed = newSupport(6, 8).buildScenarioSeed(NullNode.getInstance(), coreMethods());

        List<String> all = new ArrayList<>();
        for (JsonNode scenario : seed) {
            all.addAll(stepFqns(scenario));
        }
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("대표 흐름에서 쓴 메서드는 뒤 시나리오에서 다시 쓰지 않는다")
    void doesNotRepeatMethodsAlreadyCoveredByFlow() {
        JsonNode seed = newSupport(6, 8).buildScenarioSeed(realMethodFlow(), coreMethods());

        List<String> all = new ArrayList<>();
        for (JsonNode scenario : seed) {
            all.addAll(stepFqns(scenario));
        }
        assertThat(all).doesNotHaveDuplicates();
        assertThat(all).contains("com.acme.Builder.required", "com.acme.Parser.parse");
    }

    @Test
    @DisplayName("각 단계에 근거 위치와 summarySeed를 실어 모델이 지어내지 않게 한다")
    void stepsCarryEvidenceAnchorAndSummary() {
        JsonNode seed = newSupport(6, 8).buildScenarioSeed(NullNode.getInstance(), coreMethods());

        JsonNode step = seed.get(0).path("steps").get(0);
        assertThat(step.path("filePath").asText()).isEqualTo("src/main/java/com/acme/Builder.java");
        assertThat(step.path("startLine").asInt()).isEqualTo(10);
        assertThat(step.path("summarySeed").asText()).contains("필수 옵션");
        assertThat(step.path("signatureHint").asText()).contains("Builder");
    }

    @Test
    @DisplayName("시나리오 상한을 넘기지 않는다")
    void respectsScenarioCap() {
        JsonNode seed = newSupport(1, 8).buildScenarioSeed(NullNode.getInstance(), coreMethods());

        assertThat(seed.size()).isEqualTo(1);
        assertThat(seed.get(0).path("title").asText()).isEqualTo("Builder 사용 흐름");
    }

    private List<String> stepFqns(JsonNode scenario) {
        List<String> out = new ArrayList<>();
        for (JsonNode step : scenario.path("steps")) {
            out.add(step.path("methodFqn").asText());
        }
        return out;
    }

    private JsonNode realMethodFlow() {
        ArrayNode flow = objectMapper.createArrayNode();
        flow.addObject()
                .put("order", 1)
                .put("title", "옵션 정의")
                .put("description", "필수 옵션을 먼저 구성합니다.")
                .put("classFqn", "com.acme.Builder")
                .put("methodFqn", "com.acme.Builder.required")
                .put("filePath", "src/main/java/com/acme/Builder.java")
                .put("startLine", 10)
                .put("endLine", 20);
        flow.addObject()
                .put("order", 2)
                .put("title", "파싱 실행")
                .put("description", "입력 인자를 파서에 전달합니다.")
                .put("classFqn", "com.acme.Parser")
                .put("methodFqn", "com.acme.Parser.parse")
                .put("filePath", "src/main/java/com/acme/Parser.java")
                .put("startLine", 30)
                .put("endLine", 55);
        return flow;
    }

    private CoreMethodSeed method(String cls, String name, int importance, int start, int end) {
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        return new CoreMethodSeed(
                "sym-" + cls + "#" + name, "cls-" + cls, cls, simple, name,
                cls + "." + name, "src/main/java/" + cls.replace('.', '/') + ".java",
                importance, simple + " " + name + "(...)", name + " 관련 동작", "SCN-기본사용",
                start, end
        );
    }

    private List<CoreMethodSeed> coreMethods() {
        return List.of(
                new CoreMethodSeed(
                        "sym-1", "cls-1", "com.acme.Builder", "Builder", "required",
                        "com.acme.Builder.required", "src/main/java/com/acme/Builder.java", 100,
                        "Builder required(String name)", "필수 옵션을 등록한다.", "SCN-옵션정의", 10, 20
                ),
                method("com.acme.Builder", "optional", 95, 22, 30),
                // 오버로드: fqn이 같아 한 단계로 접혀야 한다.
                method("com.acme.Builder", "optional", 94, 32, 40),
                method("com.acme.Builder", "hashCode", 93, 42, 45),
                method("com.acme.Parser", "parse", 90, 30, 55),
                method("com.acme.Parser", "getValue", 80, 60, 70),
                method("com.acme.Parser", "toString", 79, 72, 75),
                // 공개 메서드가 하나뿐이라 시나리오가 될 수 없다.
                method("com.acme.Lonely", "run", 88, 5, 9)
        );
    }
}
