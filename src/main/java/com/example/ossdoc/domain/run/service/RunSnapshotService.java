package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.support.GithubClient;
import com.example.ossdoc.domain.run.support.JobManifestWriter;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.domain.run.support.ZipUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;

/*
 * SNAPSHOT 단계 서비스입니다.
 *
 * repo zip 다운로드, 압축 해제, job_manifest.json 생성을 담당합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunSnapshotService {

    private final RepoRunRepository repoRunRepository;
    private final GithubClient githubClient;
    private final WorkspaceManager workspaceManager;
    private final JobManifestWriter jobManifestWriter;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public void prepareSnapshot(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));

        Path workspaceRoot = Path.of(run.getWorkspaceRoot());
        Path zipPath = workspaceRoot.resolve("repo.zip");
        Path unzipRoot = workspaceRoot.resolve("repo");

        githubClient.downloadZip(
                run.getRepoOwner(),
                run.getRepoName(),
                run.getCommitSha(),
                zipPath
        );

        ZipUtils.unzip(zipPath, unzipRoot);

        log.info(
                "[SNAPSHOT] Repository snapshot ready runId={}, zipPath={}, unzipRoot={}",
                runId,
                zipPath,
                unzipRoot
        );

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("runId", runId);
        meta.put("repoUrl", run.getRepoUrl());
        meta.put("owner", run.getRepoOwner());
        meta.put("repo", run.getRepoName());
        meta.put("ref", run.getResolvedRef());
        meta.put("commitSha", run.getCommitSha());
        meta.put("workspaceRoot", workspaceRoot.toString());
        meta.put("generatedAt", OffsetDateTime.now().toString());

        Path artifactsDir = workspaceManager.artifactsDir(workspaceRoot);

        jobManifestWriter.write(artifactsDir, meta);

        artifactService.saveJsonArtifact(
                run,
                ArtifactKind.JOB_MANIFEST,
                "0.1",
                "job_manifest.json",
                meta
        );
    }
}