package com.example.ossdoc.domain.run.entity;

import com.example.ossdoc.domain.run.enums.RunStatus;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.run.enums.AnalysisAccessType;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.example.ossdoc.global.llm.enums.LlmProvider;
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

    /**
     * 이 run의 LLM 단계가 쓸 제공자입니다.
     *
     * 왜 run에 남기는가:
     * - 제공자를 요청 시점에 고르면, 실제로 LLM이 도는 시점(파이프라인 워커)까지 그 선택을
     *   실어 나를 곳이 필요합니다. 요청과 실행이 비동기로 갈라져 있어 요청 객체로는 닿지 않습니다.
     * - 산출물이 어느 모델에서 나왔는지 사후에 확인할 수 있어야 합니다.
     *
     * nullable인 이유:
     * - 이 기능 이전에 만들어진 run과, 제공자를 지정하지 않은 요청을 그대로 살립니다.
     *   null이면 실행 시점에 ossdoc.llm.provider 설정값이 쓰입니다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "llm_provider", length = 20)
    private LlmProvider llmProvider;

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
    /**
     * LLM 제공자를 확정합니다.
     *
     * 생성자 파라미터로 받지 않은 이유: 기존 생성자 호출 지점(운영 3곳 + 테스트 7곳)을
     * 모두 건드리지 않고, 제공자를 쓰는 경로에서만 명시적으로 지정하기 위해서입니다.
     */
    public void assignLlmProvider(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
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