package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.run.dto.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.enums.RunStatus;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.support.GithubClient;
import com.example.ossdoc.domain.run.support.GithubRepoRef;
import com.example.ossdoc.domain.run.support.GithubUrlParser;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.domain.run.support.ZipUtils;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoRunService {

    private final RepoRunRepository repoRunRepository;
    private final UserRepository userRepository;
    private final ArtifactService artifactService;
    private final GithubClient githubClient;
    private final WorkspaceManager workspaceManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public RepoRunCreateResponse createRun(RepoRunCreateRequest req, Long userId) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));

        GithubRepoRef parsed = GithubUrlParser.parse(req.getRepoUrl(), req.getRef());
        log.info(
                "Create run requested userId={}, owner={}, repo={}, requestedRef={}",
                userId,
                parsed.getOwner(),
                parsed.getRepo(),
                req.getRef()
        );

        String ref = parsed.getRef();
        if (ref == null || ref.isBlank()) {
            ref = githubClient.resolveDefaultBranch(parsed.getOwner(), parsed.getRepo());
            log.info("Resolved default branch owner={}, repo={}, ref={}", parsed.getOwner(), parsed.getRepo(), ref);
        }

        String commitSha = githubClient.resolveCommitSha(parsed.getOwner(), parsed.getRepo(), ref);
        log.info(
                "Resolved commit SHA owner={}, repo={}, ref={}, sha={}",
                parsed.getOwner(),
                parsed.getRepo(),
                ref,
                abbreviateSha(commitSha)
        );

        String runId = "run_" + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
                + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path wsRoot = workspaceManager.workspaceRoot(runId);
        log.info("Workspace prepared runId={}, workspaceRoot={}", runId, wsRoot);

        Path zipPath = wsRoot.resolve("repo.zip");
        githubClient.downloadZip(parsed.getOwner(), parsed.getRepo(), commitSha, zipPath);

        Path unzipRoot = wsRoot.resolve("repo");
        ZipUtils.unzip(zipPath, unzipRoot);
        log.info("Repository snapshot ready runId={}, zipPath={}, unzipRoot={}", runId, zipPath, unzipRoot);

        RepoRun run = new RepoRun(
                runId,
                owner,
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

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("ref", ref);
        meta.put("commitSha", commitSha);
        meta.put("generatedAt", OffsetDateTime.now().toString());

        artifactService.saveJsonArtifact(run, ArtifactKind.JOB_MANIFEST, "0.1",
                "job_manifest.json", meta);
        log.info("Run queued runId={}, status={}, sha={}", runId, run.getStatus(), abbreviateSha(commitSha));

        return RepoRunCreateResponse.builder()
                .runId(runId)
                .status(run.getStatus())
                .commitSha(commitSha)
                .workspaceRoot(wsRoot.toString())
                .build();
    }

    private String abbreviateSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "<empty>";
        }
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }
}
