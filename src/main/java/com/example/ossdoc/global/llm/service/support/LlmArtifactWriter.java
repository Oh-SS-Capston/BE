package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.global.llm.config.LlmOutputProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LLM 산출물 저장 경로 분기.
 *
 * <p>기본은 기존 흐름 그대로 {@link ArtifactService#saveJsonArtifact}에 위임한다(S3 + 로컬 + DB).
 * {@code ossdoc.llm.output.local-only=true}이면 로컬 파일만 남긴다.</p>
 *
 * <p>ArtifactService 자체를 고치지 않고 여기서 분기하는 이유: saveJsonArtifact는
 * build/cluster/publicapi/rule 등 8개 단계가 공유하고 반환 Artifact를 쓰는 호출부도 많다.
 * 실험용 토글 때문에 동작 중인 파이프라인 전체의 저장 흐름을 건드릴 이유가 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmArtifactWriter {

    private final ArtifactService artifactService;
    private final WorkspaceManager workspaceManager;
    private final ObjectMapper objectMapper;
    private final LlmOutputProperties llmOutputProperties;

    public void write(
            RepoRun run,
            ArtifactKind kind,
            String schemaVersion,
            String relativePath,
            JsonNode content
    ) {
        if (!llmOutputProperties.isLocalOnly()) {
            artifactService.saveJsonArtifact(run, kind, schemaVersion, relativePath, content);
            return;
        }
        writeLocalOnly(run.getRunId(), kind, relativePath, content);
    }

    /**
     * 로컬 전용 저장.
     * 여기서는 실패를 삼키지 않는다. 로컬이 유일한 산출물이므로 실패하면 알아야 한다.
     */
    private void writeLocalOnly(String runId, ArtifactKind kind, String relativePath, JsonNode content) {
        try {
            Path out = workspaceManager.artifactsDir(workspaceManager.workspaceRoot(runId))
                    .resolve(relativePath);
            Files.createDirectories(out.getParent());
            Files.writeString(out, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content));
            log.info("[LlmArtifactWriter] 로컬 전용 저장 (S3/DB 생략) — kind={}, path={}", kind, out);
        } catch (Exception e) {
            log.error("[LlmArtifactWriter] 로컬 저장 실패 — kind={}, path={}, message={}",
                    kind, relativePath, e.getMessage());
            throw new IllegalStateException("LLM 산출물 로컬 저장 실패: " + relativePath, e);
        }
    }
}
