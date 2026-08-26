package com.example.ossdoc.domain.artifact.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.global.s3.exception.S3Exception;
import com.example.ossdoc.global.s3.exception.code.S3ErrorCode;
import com.example.ossdoc.global.s3.util.S3Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Artifact 저장 서비스.
 *
 * <p><b>저장 흐름</b>
 * <pre>
 *   1. JSON 직렬화  →  실패 시 S3Exception(S3_500_003)
 *   2. S3 업로드    →  실패 시 S3Exception(S3_500_001)
 *   3. DB 저장      →  Artifact.path = S3 HTTPS URL
 * </pre>
 *
 * <p><b>S3 키 구조</b>
 * <pre>
 *   artifacts/
 *   └── {runId}/
 *       ├── job_manifest.json
 *       ├── build_manifest.json
 *       ├── facts.json
 *       ├── graph_stats.json
 *       └── llm/
 *           ├── refined_rules.json
 *           ├── scenario_specs.json
 *           ├── subsystem_summaries.json
 *           └── api_docs.json
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactService {

    private final ArtifactRepository artifactRepository;
    private final S3Util             s3Util;
    private final ObjectMapper       objectMapper;
    private final WorkspaceManager   workspaceManager;

    /**
     * JSON Artifact를 S3에 업로드하고 DB에 URL 경로를 저장한다.
     *
     * @param run           연관된 RepoRun
     * @param kind          Artifact 종류 (ArtifactKind 열거형)
     * @param schemaVersion JSON 스키마 버전 문자열 (예: "1.0")
     * @param relativePath  S3 상의 상대 경로 (예: "llm/refined_rules.json")
     *                      — runId 프리픽스는 자동으로 붙음
     * @param content       저장할 JSON 본문
     * @return              저장된 Artifact 엔티티 (path = S3 HTTPS URL)
     * @throws S3Exception  직렬화 실패(S3_500_003) 또는 업로드 실패(S3_500_001) 시
     */
    @Transactional
    public Artifact saveJsonArtifact(RepoRun run,
                                     ArtifactKind kind,
                                     String schemaVersion,
                                     String relativePath,
                                     JsonNode content) {

        // 1. JSON 직렬화
        String jsonBody = serialize(content);

        // 2. S3 업로드  (키 = artifacts/{runId}/{relativePath})
        String s3Key = buildS3Key(run.getRunId(), relativePath);
        String s3Url = s3Util.uploadJson(s3Key, jsonBody);

        log.info("[ArtifactService] S3 업로드 완료 — kind: {}, url: {}", kind, s3Url);

        // 3. 로컬 저장 ({baseDir}/{runId}/artifacts/{relativePath})
        saveToLocal(run.getRunId(), relativePath, jsonBody);

        // 4. DB 저장 (path = S3 URL)
        return artifactRepository.findByRun_RunIdAndKindAndPath(run.getRunId(), kind, s3Url)
                .map(existing -> {
                    existing.overwriteJsonContent(schemaVersion, "application/json", s3Url, content);
                    return existing;
                })
                .orElseGet(() -> artifactRepository.save(new Artifact(
                        null,
                        run,
                        kind,
                        schemaVersion,
                        "application/json",
                        s3Url,
                        content
                )));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveToLocal(String runId, String relativePath, String jsonBody) {
        try {
            Path out = workspaceManager.artifactsDir(workspaceManager.workspaceRoot(runId))
                    .resolve(relativePath);
            Files.createDirectories(out.getParent());
            Files.writeString(out, jsonBody);
            log.info("[ArtifactService] 로컬 저장 완료 — {}", out);
        } catch (Exception e) {
            log.warn("[ArtifactService] 로컬 저장 실패 (S3 저장은 완료): {}", e.getMessage());
        }
    }

    /**
     * S3 객체 키 조립.
     * buildS3Key("run_20260315_abc12345", "llm/refined_rules.json")
     *   → "artifacts/run_20260315_abc12345/llm/refined_rules.json"
     */
    private String buildS3Key(String runId, String relativePath) {
        String clean = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return String.format("artifacts/%s/%s", runId, clean);
    }

    /**
     * JsonNode → JSON 문자열 변환.
     * 실패 시 IllegalStateException 대신 프로젝트 공통 S3Exception 으로 변환한다.
     */
    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("[ArtifactService] JSON 직렬화 실패 — {}", e.getMessage());
            throw new S3Exception(S3ErrorCode.JSON_SERIALIZE_FAILED);
        }
    }
}
