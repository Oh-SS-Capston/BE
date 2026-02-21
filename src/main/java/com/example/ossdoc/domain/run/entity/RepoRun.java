package com.example.ossdoc.domain.run.entity;

import com.example.ossdoc.domain.run.enums.RunStatus;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "repo_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor

public class RepoRun extends BaseAuditedEntity {

    @Id
    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

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
}