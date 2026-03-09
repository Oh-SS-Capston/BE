// domain/run/support/WorkspaceManager.java
package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.exception.RunException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceManager {

    private final ObjectMapper objectMapper;

    // TODO: application.yml로 빼는 걸 추천 (ex. ossdoc.workspace.base-dir=/data/ossdoc)
    // 모든 Run 작업이 저장되는 루트 경로
    private final String baseDir = "/data/ossdoc";

    // 특정 Run의 최상위 작업 디렉토리 경로 반환
    /**
     이 디렉토리 안에: unzip된 소스,
     artifacts,
     facts.json,
     graph.json,
     build 결과,
     전부 들어가게 됨
     **/
    public Path workspaceRoot(String runId) {
        return Path.of(baseDir, runId);
    }

    // 분석 결과물이 저장될 폴더 경로 반환 (job_manifest.json, build_manifest.json, facts.json, graph_stats.json, rule_candidates.json, rendered diagrams)
    public Path artifactsDir(Path workspaceRoot) {
        return workspaceRoot.resolve("artifacts");
    }

    // Run이 어떤 코드 스냅샷을 분석했는지 기록하는 공식 선언문 생성 (job_manifest.json)
    public Path writeJobManifest(
            Path workspaceRoot,
            String runId,
            String repoUrl,
            String owner,
            String repo,
            String ref,
            String commitSha,
            Path unzipRoot
    ) {
        try {
            Files.createDirectories(artifactsDir(workspaceRoot));
            Path manifestPath = artifactsDir(workspaceRoot).resolve("job_manifest.json");

            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode repoRun = root.putObject("repoRun");
            repoRun.put("runId", runId);

            ObjectNode source = repoRun.putObject("source");
            source.put("type", "github");
            source.put("url", repoUrl);
            source.put("owner", owner);
            source.put("repo", repo);
            source.put("ref", ref);
            source.put("commitSha", commitSha);

            ObjectNode paths = repoRun.putObject("paths");
            paths.put("workspaceRoot", workspaceRoot.toString());
            paths.put("repoRoot", unzipRoot.toString());

            repoRun.put("generatedAt", OffsetDateTime.now().toString());

            Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            return manifestPath;
        } catch (Exception e) {
            log.debug("manifest write fail error={}", e.getMessage());
            throw new RunException(RunErrorCode.MANIFEST_WRITE_FAILED);
        }
    }
}