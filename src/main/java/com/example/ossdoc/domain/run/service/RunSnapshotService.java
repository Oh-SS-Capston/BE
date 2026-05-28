package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.enums.SnapshotMode;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.support.GitCloneClient;
import com.example.ossdoc.domain.run.support.GithubClient;
import com.example.ossdoc.domain.run.support.JobManifestWriter;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.domain.run.support.ZipUtils;
import com.example.ossdoc.global.properties.RunSnapshotProperties;
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
 * repo 가져오기(zip 또는 git clone), 압축 해제(zip), job_manifest.json 생성을 담당합니다.
 * 수집 방식은 RunSnapshotProperties.mode (ZIP|CLONE) 설정으로 전환합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunSnapshotService {

    private final RepoRunRepository repoRunRepository;
    private final GithubClient githubClient;
    private final GitCloneClient gitCloneClient;
    private final WorkspaceManager workspaceManager;
    private final JobManifestWriter jobManifestWriter;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final RunSnapshotProperties snapshotProperties;

    public void prepareSnapshot(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));

        Path workspaceRoot = Path.of(run.getWorkspaceRoot());
        Path unzipRoot = workspaceRoot.resolve("repo");

        SnapshotMode mode = snapshotProperties.getMode();
        log.info("[SNAPSHOT] Start runId={}, mode={}, workspaceRoot={}", runId, mode, workspaceRoot);

        switch (mode) {
            case CLONE -> prepareViaClone(run, unzipRoot);
            case ZIP -> prepareViaZip(run, workspaceRoot, unzipRoot);
        }

        log.info("[SNAPSHOT] Repository snapshot ready runId={}, mode={}, repoRoot={}", runId, mode, unzipRoot);

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("runId", runId);
        meta.put("repoUrl", run.getRepoUrl());
        meta.put("owner", run.getRepoOwner());
        meta.put("repo", run.getRepoName());
        meta.put("ref", run.getResolvedRef());
        meta.put("commitSha", run.getCommitSha());
        meta.put("workspaceRoot", workspaceRoot.toString());
        meta.put("snapshotMode", mode.name());
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

    /**
     * ZIP 모드 — codeload zip 다운로드 후 unzipRoot에 압축 해제한다.
     * 결과 구조: {workspaceRoot}/repo/{owner}-{repo}-{sha}/...
     */
    private void prepareViaZip(RepoRun run, Path workspaceRoot, Path unzipRoot) {
        Path zipPath = workspaceRoot.resolve("repo.zip");

        githubClient.downloadZip(
                run.getRepoOwner(),
                run.getRepoName(),
                run.getCommitSha(),
                zipPath
        );

        ZipUtils.unzip(zipPath, unzipRoot);

        log.info("[SNAPSHOT] ZIP snapshot prepared zipPath={}, unzipRoot={}", zipPath, unzipRoot);
    }

    /**
     * CLONE 모드 — shallow git fetch 후 commit SHA에 체크아웃한다.
     * 결과 구조: {workspaceRoot}/repo/...  (한 단계 더 들어가지 않음. RepoRootResolver가 두 형태 모두 처리)
     */
    private void prepareViaClone(RepoRun run, Path unzipRoot) {
        gitCloneClient.cloneAtCommit(
                run.getRepoOwner(),
                run.getRepoName(),
                run.getCommitSha(),
                unzipRoot
        );

        log.info("[SNAPSHOT] CLONE snapshot prepared repoRoot={}", unzipRoot);
    }
}
