package com.example.ossdoc.domain.run.entity;

import com.example.ossdoc.domain.run.enums.RunStatus;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.membership.enums.AnalysisAccessType;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "repo_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RepoRun extends BaseAuditedEntity {

    @Id
    @Column(name = "run_id", nullable = false)
    private String runId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "repo_owner")
    private String repoOwner;

    @Column(name = "repo_name")
    private String repoName;

    @Column(name = "resolved_ref")
    private String resolvedRef;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    /*
     * run의 대표 최종 상태만 저장합니다.
     * 세부 진행률은 RunPipelineJob이 담당합니다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RunStatus status = RunStatus.QUEUED;

    @Column(name = "primary_language")
    private String primaryLanguage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "build_system", columnDefinition = "jsonb")
    private JsonNode buildSystem;

    @Column(name = "license")
    private String license;

    @Column(name = "repo_size_bytes")
    private Long repoSizeBytes;

    @Column(name = "workspace_root")
    private String workspaceRoot;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_access_type", length = 30)
    private AnalysisAccessType analysisAccessType;

    public RepoRun(
            String runId,
            User owner,
            String repoUrl,
            String repoOwner,
            String repoName,
            String resolvedRef,
            String commitSha,
            String workspaceRoot,
            AnalysisAccessType analysisAccessType
    ) {
        this.runId = runId;
        this.owner = owner;
        this.repoUrl = repoUrl;
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.resolvedRef = resolvedRef;
        this.commitSha = commitSha;
        this.workspaceRoot = workspaceRoot;
        this.analysisAccessType = analysisAccessType;
        this.status = RunStatus.QUEUED;
    }

    public void assignOwner(User owner) {
        this.owner = owner;
    }
    public void assignAnalysisAccessType(AnalysisAccessType analysisAccessType){
        this.analysisAccessType = analysisAccessType;
    }

    public void markRunning() {
        this.status = RunStatus.RUNNING;
    }

    public void markSuccess() {
        this.status = RunStatus.SUCCESS;
    }

    public void markPartialSuccess() {
        this.status = RunStatus.PARTIAL_SUCCESS;
    }

    public void markFailed() {
        this.status = RunStatus.FAILED;
    }
}