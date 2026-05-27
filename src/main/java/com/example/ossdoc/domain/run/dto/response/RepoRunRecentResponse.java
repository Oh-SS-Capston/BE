package com.example.ossdoc.domain.run.dto.response;

import com.example.ossdoc.domain.membership.enums.AnalysisAccessType;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.enums.RunStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class RepoRunRecentResponse {
    private String runId;
    private String repoUrl;

    /*
     * 프론트에서 /analyze?repo=owner/repo 형태로 넘기기 위한 값입니다.
     */
    private String repoFullName;

    private String repoOwner;
    private String repoName;
    private String resolvedRef;
    private String commitSha;
    private RunStatus status;
    private AnalysisAccessType analysisAccessType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RepoRunRecentResponse from(RepoRun run) {
        return RepoRunRecentResponse.builder()
                .runId(run.getRunId())
                .repoUrl(run.getRepoUrl())
                .repoFullName(resolveRepoFullName(run))
                .repoOwner(run.getRepoOwner())
                .repoName(run.getRepoName())
                .resolvedRef(run.getResolvedRef())
                .commitSha(run.getCommitSha())
                .status(run.getStatus())
                .status(run.getStatus())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    private static String resolveRepoFullName(RepoRun run) {
        if (run.getRepoOwner() != null && run.getRepoName() != null) {
            return run.getRepoOwner() + "/" + run.getRepoName();
        }

        if (run.getRepoUrl() == null || run.getRepoUrl().isBlank()) {
            return "";
        }

        String normalized = run.getRepoUrl()
                .replace("https://github.com/", "")
                .replace("http://github.com/", "")
                .replace(".git", "");

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }
}