package com.example.ossdoc.domain.run.cache.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.repository.AnalysisCacheRepository;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisKeyPolicy;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisStore;
import com.example.ossdoc.domain.run.cache.support.ReadyPayload;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeyFactory;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeySeed;
import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * W08: 파이프라인 성공 시 분석 캐시 READY를 발행하는 서비스입니다.
 *
 * 발행 정책:
 * - 필수 산출물이 모두 준비된 경우에만 READY를 기록합니다.
 * - 필수 산출물 누락 시 READY 발행을 금지하고 false를 반환합니다.
 * - READY 발행이 성공하면 Redis ready 키도 함께 동기화합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisCachePublishService {

    /**
     * READY 발행에 반드시 필요한 산출물 목록입니다.
     * UI 즉시 로딩에 필요한 구조 분석/LLM 결과를 최소 세트로 고정합니다.
     */
    private static final List<ArtifactKind> REQUIRED_READY_ARTIFACT_KINDS = List.of(
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

    /**
     * 있으면 함께 번들에 담는 선택 산출물입니다.
     * symbol_source_index는 점진 도입 중이므로 없어도 READY 발행을 막지 않습니다.
     */
    private static final List<ArtifactKind> OPTIONAL_ARTIFACT_KINDS = List.of(
            ArtifactKind.SYMBOL_SOURCE_INDEX_JSON
    );

    private final RepoRunRepository repoRunRepository;
    private final ArtifactRepository artifactRepository;
    private final AnalysisCacheRepository analysisCacheRepository;
    private final RunAnalysisCacheKeyFactory runAnalysisCacheKeyFactory;
    private final AnalysisCacheProperties analysisCacheProperties;
    private final AnalysisCacheRedisKeyPolicy redisKeyPolicy;
    private final AnalysisCacheRedisStore redisStore;
    private final ObjectMapper objectMapper;

    @Transactional
    public boolean publishReady(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElse(null);
        if (run == null) {
            log.warn("[CACHE] READY publish skipped. run not found. runId={}", runId);
            return false;
        }
        return publishReady(run);
    }

    @Transactional
    public boolean publishReady(RepoRun run) {
        String cacheKey = buildCacheKey(run);
        String repoUrlNorm = runAnalysisCacheKeyFactory.normalizeRepoUrlForCache(run.getRepoUrl());

        Map<ArtifactKind, Artifact> requiredArtifacts = loadArtifacts(run.getRunId(), REQUIRED_READY_ARTIFACT_KINDS);
        List<ArtifactKind> missing = REQUIRED_READY_ARTIFACT_KINDS.stream()
                .filter(kind -> !requiredArtifacts.containsKey(kind))
                .toList();

        if (!missing.isEmpty()) {
            log.warn(
                    "[CACHE] READY publish blocked. missing required artifacts. runId={}, cacheKey={}, missing={}",
                    run.getRunId(),
                    abbreviate(cacheKey),
                    missing
            );
            return false;
        }

        Map<ArtifactKind, Artifact> optionalArtifacts = loadArtifacts(run.getRunId(), OPTIONAL_ARTIFACT_KINDS);
        ObjectNode artifactBundle = buildArtifactBundle(run, cacheKey, repoUrlNorm, requiredArtifacts, optionalArtifacts);
        String qualityHash = computeQualityHash(run, requiredArtifacts, optionalArtifacts);

        LocalDateTime expiresAt = LocalDateTime.now().plus(redisKeyPolicy.readyTtl());

        AnalysisCache cache = analysisCacheRepository.findById(cacheKey)
                .orElseGet(() -> new AnalysisCache(cacheKey, repoUrlNorm, run.getCommitSha()));
        cache.markReady(run.getRunId(), artifactBundle, qualityHash, expiresAt);
        analysisCacheRepository.save(cache);

        syncRedisReady(cache.getCacheKey(), cache.getSourceRunId());

        log.info(
                "[CACHE] READY published. runId={}, cacheKey={}, requiredCount={}, optionalCount={}",
                run.getRunId(),
                abbreviate(cacheKey),
                requiredArtifacts.size(),
                optionalArtifacts.size()
        );
        return true;
    }

    private String buildCacheKey(RepoRun run) {
        RunAnalysisCacheKeySeed seed = RunAnalysisCacheKeySeed.builder()
                .repoUrl(run.getRepoUrl())
                .commitSha(run.getCommitSha())
                .pipelineContractVersion(analysisCacheProperties.getPipelineContractVersion())
                .llmProfileVersion(analysisCacheProperties.getLlmProfileVersion())
                .promptTemplateVersion(analysisCacheProperties.getPromptTemplateVersion())
                .outputSchemaVersion(analysisCacheProperties.getOutputSchemaVersion())
                .runOptionsSignature(analysisCacheProperties.getDefaultRunOptionsSignature())
                .build();
        return runAnalysisCacheKeyFactory.buildKey(seed);
    }

    private Map<ArtifactKind, Artifact> loadArtifacts(String runId, List<ArtifactKind> kinds) {
        Map<ArtifactKind, Artifact> result = new EnumMap<>(ArtifactKind.class);
        for (ArtifactKind kind : kinds) {
            Optional<Artifact> artifact = artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, kind);
            artifact.ifPresent(value -> result.put(kind, value));
        }
        return result;
    }

    /**
     * analysis_cache.artifact_bundle_json에 저장할 메타 구조를 만듭니다.
     * kind별 산출물 위치/식별자를 모아 이후 캐시 응답 최적화에 사용합니다.
     */
    private ObjectNode buildArtifactBundle(
            RepoRun run,
            String cacheKey,
            String repoUrlNorm,
            Map<ArtifactKind, Artifact> requiredArtifacts,
            Map<ArtifactKind, Artifact> optionalArtifacts
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("runId", run.getRunId());
        root.put("cacheKey", cacheKey);
        root.put("repoUrlNorm", repoUrlNorm);
        root.put("commitSha", run.getCommitSha());
        root.put("generatedAt", LocalDateTime.now().toString());

        ObjectNode requiredNode = root.putObject("requiredArtifacts");
        requiredArtifacts.forEach((kind, artifact) -> requiredNode.set(kind.name(), toArtifactNode(artifact)));

        ObjectNode optionalNode = root.putObject("optionalArtifacts");
        optionalArtifacts.forEach((kind, artifact) -> optionalNode.set(kind.name(), toArtifactNode(artifact)));

        return root;
    }

    private ObjectNode toArtifactNode(Artifact artifact) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("artifactId", artifact.getArtifactId());
        node.put("kind", artifact.getKind().name());
        node.put("schemaVersion", artifact.getSchemaVersion());
        node.put("contentType", artifact.getContentType());
        node.put("path", artifact.getPath());
        node.put("createdAt", artifact.getCreatedAt() == null ? null : artifact.getCreatedAt().toString());
        return node;
    }

    /**
     * 품질 시그니처(qualityHash)를 계산합니다.
     * 동일 결과 세트면 동일 해시가 나오도록 kind/artifactId/path를 고정 순서로 직렬화합니다.
     */
    private String computeQualityHash(
            RepoRun run,
            Map<ArtifactKind, Artifact> requiredArtifacts,
            Map<ArtifactKind, Artifact> optionalArtifacts
    ) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("runId=").append(run.getRunId()).append('\n');
        canonical.append("commitSha=").append(run.getCommitSha()).append('\n');

        REQUIRED_READY_ARTIFACT_KINDS.forEach(kind ->
                appendArtifactFingerprint(canonical, kind, requiredArtifacts.get(kind))
        );
        OPTIONAL_ARTIFACT_KINDS.forEach(kind -> {
            Artifact artifact = optionalArtifacts.get(kind);
            if (artifact != null) {
                appendArtifactFingerprint(canonical, kind, artifact);
            }
        });

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            log.warn("[CACHE] quality hash generation failed. runId={}", run.getRunId(), e);
            return null;
        }
    }

    private void appendArtifactFingerprint(StringBuilder out, ArtifactKind kind, Artifact artifact) {
        if (artifact == null) {
            out.append(kind.name()).append("=<missing>").append('\n');
            return;
        }
        out.append(kind.name())
                .append('=')
                .append(artifact.getArtifactId()).append('|')
                .append(artifact.getPath()).append('|')
                .append(artifact.getSchemaVersion())
                .append('\n');
    }

    private void syncRedisReady(String cacheKey, String sourceRunId) {
        try {
            String readyKey = redisKeyPolicy.readyKey(cacheKey);
            String payload = objectMapper.writeValueAsString(new ReadyPayload(cacheKey, sourceRunId));
            redisStore.set(readyKey, payload, redisKeyPolicy.readyTtl());
        } catch (JsonProcessingException e) {
            log.warn("[CACHE] failed to serialize redis ready payload. cacheKey={}", abbreviate(cacheKey), e);
        }
    }

    private String abbreviate(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return "<empty>";
        }
        return cacheKey.length() <= 12 ? cacheKey : cacheKey.substring(0, 12);
    }
}
