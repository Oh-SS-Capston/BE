package com.example.ossdoc.domain.rule.entity;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "rule_candidate_summary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RuleCandidateSummary extends BaseAuditedEntity {

    @EmbeddedId
    private RuleCandidateSummaryId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("runId")
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "fingerprint", nullable = false)
    private String fingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", nullable = false, columnDefinition = "jsonb")
    private JsonNode summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "impact", nullable = false, columnDefinition = "jsonb")
    private JsonNode impact;
}