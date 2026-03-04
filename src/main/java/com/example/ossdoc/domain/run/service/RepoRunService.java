// domain/run/service/RepoRunService.java
package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.run.dto.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.enums.RunStatus;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.support.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepoRunService {

    private final RepoRunRepository repoRunRepository;
    private final ArtifactService artifactService;

    private final GithubClient githubClient;
    private final WorkspaceManager workspaceManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public RepoRunCreateResponse createRun(RepoRunCreateRequest req) {
        // 1) URL 파싱
        GithubRepoRef parsed = GithubUrlParser.parse(req.getRepoUrl(), req.getRef());

        // 2) ref 결정 (없으면 default_branch)
        String ref = parsed.getRef();
        if (ref == null || ref.isBlank()) {
            ref = githubClient.resolveDefaultBranch(parsed.getOwner(), parsed.getRepo());
        }

        // 3) commit SHA 확정 (재현성 핵심)
        String commitSha = githubClient.resolveCommitSha(parsed.getOwner(), parsed.getRepo(), ref);

        // 4) runId / workspace 경로
        String runId = "run_" + OffsetDateTime.now().toLocalDate().toString().replace("-", "") + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path wsRoot = workspaceManager.workspaceRoot(runId);

        // 5) zip 다운로드 → unzip
        Path zipPath = wsRoot.resolve("repo.zip");
        githubClient.downloadZip(parsed.getOwner(), parsed.getRepo(), commitSha, zipPath);

        // GitHub zip은 보통 최상위 폴더가 "{repo}-{sha}" 형태로 한 번 감싸져 있음
        Path unzipRoot = wsRoot.resolve("repo");
        ZipUtils.unzip(zipPath, unzipRoot);

        // 6) job_manifest.json 생성
        Path manifestPath = workspaceManager.writeJobManifest(
                wsRoot, runId, req.getRepoUrl(),
                parsed.getOwner(), parsed.getRepo(),
                ref, commitSha, unzipRoot
        );

        // 7) DB 저장 (RepoRun)
        RepoRun run = new RepoRun(
                runId,
                req.getRepoUrl(),
                commitSha,
                RunStatus.QUEUED,
                null,
                null,
                null,
                null,
                wsRoot.toString()
        );
        repoRunRepository.save(run);

        // 8) DB 저장 (Artifact - job_manifest)
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("ref", ref);
        meta.put("commitSha", commitSha);
        meta.put("generatedAt", OffsetDateTime.now().toString());

        artifactService.saveJobManifest(run, manifestPath.toString(), meta);

        return RepoRunCreateResponse.builder()
                .runId(runId)
                .status(run.getStatus())
                .commitSha(commitSha)
                .workspaceRoot(wsRoot.toString())
                .jobManifestPath(manifestPath.toString())
                .build();
    }
}