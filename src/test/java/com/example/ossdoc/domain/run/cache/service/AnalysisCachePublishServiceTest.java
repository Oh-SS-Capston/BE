package com.example.ossdoc.domain.run.cache.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.repository.AnalysisCacheRepository;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisKeyPolicy;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisStore;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeyFactory;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeySeed;
import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisCachePublishServiceTest {

    private static final List<ArtifactKind> REQUIRED = List.of(
            ArtifactKind.API_MAP_JSON,
            ArtifactKind.API_SURFACE_JSON,
            ArtifactKind.RANKINGS_JSON,
            ArtifactKind.SUBSYSTEMS_JSON,
            ArtifactKind.RULE_CANDIDATES_JSON,
            ArtifactKind.CLASS_DIAGRAM_JSON,
            ArtifactKind.LLM_REFINED_RULES,
            ArtifactKind.LLM_SCENARIO_SPECS,
            ArtifactKind.LLM_SUBSYSTEM_SUMMARIES,
            ArtifactKind.LLM_API_DOCS,
            ArtifactKind.LLM_FILE_TREE_DOCS
    );

    @Mock
    private com.example.ossdoc.domain.run.repository.RepoRunRepository repoRunRepository;

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private AnalysisCacheRepository analysisCacheRepository;

    @Mock
    private AnalysisCacheRedisStore redisStore;

    private AnalysisCachePublishService publishService;
    private AnalysisCacheProperties properties;
    private AnalysisCacheRedisKeyPolicy redisKeyPolicy;
    private RunAnalysisCacheKeyFactory keyFactory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new AnalysisCacheProperties();
        properties.setPipelineContractVersion("pipeline-v1");
        properties.setLlmProfileVersion("llm-profile-v1");
        properties.setPromptTemplateVersion("prompt-v1");
        properties.setOutputSchemaVersion("schema-v1");
        properties.setDefaultRunOptionsSignature("options-default-v1");
        properties.setRedisReadyKeyPrefix("analysis:ready");
        properties.setRedisReadyTtlHours(24);

        keyFactory = new RunAnalysisCacheKeyFactory();
        redisKeyPolicy = new AnalysisCacheRedisKeyPolicy(properties);
        objectMapper = new ObjectMapper();

        publishService = new AnalysisCachePublishService(
                repoRunRepository,
                artifactRepository,
                analysisCacheRepository,
                keyFactory,
                properties,
                redisKeyPolicy,
                redisStore,
                objectMapper
        );
    }

    @Test
    void 필수_산출물이_모두_있으면_READY_캐시를_발행한다() {
        RepoRun run = run("run_001");
        String cacheKey = cacheKeyOf(run);

        when(analysisCacheRepository.findById(cacheKey)).thenReturn(Optional.empty());
        stubArtifacts(run, buildRequiredArtifactMap(run), "FULL");

        boolean published = publishService.publishReady(run);

        assertThat(published).isTrue();

        ArgumentCaptor<AnalysisCache> cacheCaptor = ArgumentCaptor.forClass(AnalysisCache.class);
        verify(analysisCacheRepository).save(cacheCaptor.capture());

        AnalysisCache saved = cacheCaptor.getValue();
        assertThat(saved.getCacheKey()).isEqualTo(cacheKey);
        assertThat(saved.getSourceRunId()).isEqualTo("run_001");
        assertThat(saved.getArtifactBundleJson()).isNotNull();
        assertThat(saved.getQualityHash()).isNotBlank();

        verify(redisStore).set(
                eq("analysis:ready:" + cacheKey),
                any(),
                eq(Duration.ofHours(24))
        );
    }

    @Test
    void 필수_산출물이_누락되면_READY_캐시_발행을_차단한다() {
        RepoRun run = run("run_002");
        Map<ArtifactKind, Artifact> partial = buildRequiredArtifactMap(run);
        partial.remove(ArtifactKind.LLM_FILE_TREE_DOCS);
        stubArtifacts(run, partial, "FULL");

        boolean published = publishService.publishReady(run);

        assertThat(published).isFalse();
        verify(analysisCacheRepository, never()).save(any());
        verify(redisStore, never()).set(any(), any(), any());
    }

    @Test
    void buildMode가_FULL이_아니면_READY_캐시_발행을_차단한다() {
        RepoRun run = run("run_003");
        Map<ArtifactKind, Artifact> required = buildRequiredArtifactMap(run);
        stubArtifacts(run, required, "SOURCE_ONLY");

        boolean published = publishService.publishReady(run);

        assertThat(published).isFalse();
        verify(analysisCacheRepository, never()).save(any());
        verify(redisStore, never()).set(any(), any(), any());
    }

    private RepoRun run(String runId) {
        return new RepoRun(
                runId,
                null,
                "https://github.com/apache/commons-cli",
                "apache",
                "commons-cli",
                "master",
                "e717fd63",
                "C:/data/ossdoc/" + runId
        );
    }

    private String cacheKeyOf(RepoRun run) {
        RunAnalysisCacheKeySeed seed = RunAnalysisCacheKeySeed.builder()
                .repoUrl(run.getRepoUrl())
                .commitSha(run.getCommitSha())
                .pipelineContractVersion(properties.getPipelineContractVersion())
                .llmProfileVersion(properties.getLlmProfileVersion())
                .promptTemplateVersion(properties.getPromptTemplateVersion())
                .outputSchemaVersion(properties.getOutputSchemaVersion())
                .runOptionsSignature(properties.getDefaultRunOptionsSignature())
                .build();
        return keyFactory.buildKey(seed);
    }

    private Map<ArtifactKind, Artifact> buildRequiredArtifactMap(RepoRun run) {
        Map<ArtifactKind, Artifact> map = new EnumMap<>(ArtifactKind.class);
        long artifactId = 1000L;
        for (ArtifactKind kind : REQUIRED) {
            ObjectNode meta = objectMapper.createObjectNode().put("kind", kind.name());
            map.put(kind, new Artifact(
                    artifactId++,
                    run,
                    kind,
                    "1.0",
                    "application/json",
                    "https://s3.example.com/" + kind.name().toLowerCase() + ".json",
                    meta
            ));
        }
        return map;
    }

    /**
     * kind별 아티팩트 조회를 맵 기반으로 스텁합니다.
     * 서비스가 어떤 순서로 조회하든 동일한 결과를 반환하도록 구성합니다.
     */
    private void stubArtifacts(RepoRun run, Map<ArtifactKind, Artifact> artifacts, String buildMode) {
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(eq(run.getRunId()), any(ArtifactKind.class)))
                .thenAnswer(invocation -> {
                    ArtifactKind kind = invocation.getArgument(1);
                    if (kind == ArtifactKind.BUILD_MANIFEST) {
                        return Optional.of(buildManifestArtifact(run, buildMode));
                    }
                    return Optional.ofNullable(artifacts.get(kind));
                });
    }

    private Artifact buildManifestArtifact(RepoRun run, String buildMode) {
        ObjectNode meta = objectMapper.createObjectNode().put("buildMode", buildMode);
        return new Artifact(
                999L,
                run,
                ArtifactKind.BUILD_MANIFEST,
                "0.1",
                "application/json",
                "https://s3.example.com/build_manifest.json",
                meta
        );
    }
}
