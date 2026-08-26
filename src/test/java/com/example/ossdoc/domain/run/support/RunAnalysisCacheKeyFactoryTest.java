package com.example.ossdoc.domain.run.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunAnalysisCacheKeyFactoryTest {

    private final RunAnalysisCacheKeyFactory factory = new RunAnalysisCacheKeyFactory();

    @Test
    void 저장소_url_표기차이가_같으면_같은_캐시_키가_생성된다() {
        RunAnalysisCacheKeySeed left = baseSeedBuilder()
                .repoUrl("https://github.com/Apache/Commons-CLI.git")
                .commitSha("E717FD63")
                .build();

        RunAnalysisCacheKeySeed right = baseSeedBuilder()
                .repoUrl("https://github.com/apache/commons-cli/")
                .commitSha("e717fd63")
                .build();

        String leftKey = factory.buildKey(left);
        String rightKey = factory.buildKey(right);

        assertThat(leftKey).isEqualTo(rightKey);
    }

    @Test
    void llm_제공자가_다르면_캐시_키가_달라진다() {
        // 같은 repo/commit이라도 ollama가 만든 산출물과 claude가 만든 산출물은 다르다.
        // 키가 같으면 claude로 요청한 run에 ollama 결과가 그대로 나간다.
        RunAnalysisCacheKeySeed ollama = baseSeedBuilder()
                .llmProvider("OLLAMA")
                .build();

        RunAnalysisCacheKeySeed claude = baseSeedBuilder()
                .llmProvider("CLAUDE")
                .build();

        assertThat(factory.buildKey(ollama)).isNotEqualTo(factory.buildKey(claude));
    }

    @Test
    void 제공자가_지정되지_않은_run은_같은_폴백_키를_쓴다() {
        // 이 기능 이전에 만들어진 run은 provider가 null이다.
        // 발행 측과 조회 측이 같은 폴백으로 정규화되어야 기존 캐시가 계속 맞는다.
        String left = factory.buildKey(baseSeedBuilder().llmProvider(null).build());
        String right = factory.buildKey(baseSeedBuilder().llmProvider("   ").build());

        assertThat(left).isEqualTo(right);
    }

    @Test
    void 버전축이_달라지면_캐시_키가_달라진다() {
        RunAnalysisCacheKeySeed oldPrompt = baseSeedBuilder()
                .promptTemplateVersion("prompt-v1")
                .build();

        RunAnalysisCacheKeySeed newPrompt = baseSeedBuilder()
                .promptTemplateVersion("prompt-v2")
                .build();

        String oldKey = factory.buildKey(oldPrompt);
        String newKey = factory.buildKey(newPrompt);

        assertThat(oldKey).isNotEqualTo(newKey);
    }

    @Test
    void 빈_버전값은_고정_fallback으로_정규화된다() {
        RunAnalysisCacheKeySeed seed = RunAnalysisCacheKeySeed.builder()
                .repoUrl("https://github.com/apache/commons-cli")
                .commitSha("e717fd63")
                .pipelineContractVersion(" ")
                .llmProfileVersion(null)
                .promptTemplateVersion("")
                .outputSchemaVersion("  ")
                .runOptionsSignature(null)
                .build();

        String payload = factory.buildCanonicalPayload(seed);

        assertThat(payload).contains("pipeline=pipeline:v1");
        assertThat(payload).contains("llm=llm:v1");
        assertThat(payload).contains("prompt=prompt:v1");
        assertThat(payload).contains("schema=schema:v1");
        assertThat(payload).contains("options=options:default");
    }

    private RunAnalysisCacheKeySeed.RunAnalysisCacheKeySeedBuilder baseSeedBuilder() {
        return RunAnalysisCacheKeySeed.builder()
                .repoUrl("https://github.com/apache/commons-cli")
                .commitSha("e717fd63")
                .pipelineContractVersion("pipeline-v1")
                .llmProfileVersion("haiku-4-5")
                .promptTemplateVersion("prompt-v1")
                .outputSchemaVersion("guide-v1")
                .runOptionsSignature("default");
    }
}
