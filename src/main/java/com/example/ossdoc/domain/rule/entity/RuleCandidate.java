package com.example.ossdoc.domain.rule.entity;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateSource;
import com.example.ossdoc.domain.rule.enums.RuleCandidateStatus;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(
        name = "rule_candidate",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "ux_rule_candidate_run_rule_key",
                        columnNames = {"run_id", "rule_key"}
                )
        },
        indexes = {
                @Index(name = "idx_rule_candidate_run", columnList = "run_id"),
                @Index(name = "idx_rule_candidate_run_kind", columnList = "run_id, candidate_kind"),
                @Index(name = "idx_rule_candidate_status", columnList = "status"),
                @Index(name = "idx_rule_candidate_confidence", columnList = "confidence"),
                @Index(name = "idx_rule_candidate_subject_symbol", columnList = "subject_symbol_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RuleCandidate extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "candidate_id")
    private Long candidateId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    /**
     * run 안에서 후보를 안정적으로 식별하는 키.
     * 예: GUARD_THROW:type:UserService#findById:IllegalArgumentException
     */
    @Column(name = "rule_key", nullable = false, length = 500)
    private String ruleKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_kind", nullable = false, length = 50)
    private RuleCandidateKind candidateKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 30)
    private RuleCandidateConfidence confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RuleCandidateStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 50)
    private RuleCandidateSource source;

    /**
     * 규칙 후보의 대표 주체 심볼.
     * 보통 메서드 심볼 또는 타입 심볼.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_symbol_id")
    private SymbolEntity subjectSymbol;

    @Column(name = "group_id", length = 300)
    private String groupId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "fingerprint", nullable = false, length = 500)
    private String fingerprint;

    @Column(name = "score", precision = 7, scale = 4)
    private BigDecimal score;

    @Column(name = "support_count", nullable = false)
    private Integer supportCount;

    @Column(name = "public_api_related", nullable = false)
    private Boolean publicApiRelated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", nullable = false, columnDefinition = "jsonb")
    private JsonNode summary = JsonNodeFactory.instance.objectNode();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "impact", nullable = false, columnDefinition = "jsonb")
    private JsonNode impact = JsonNodeFactory.instance.objectNode();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", nullable = false, columnDefinition = "jsonb")
    private JsonNode meta = JsonNodeFactory.instance.objectNode();

    public static RuleCandidate of(
            RepoRun run,
            String ruleKey,
            RuleCandidateKind candidateKind,
            RuleCandidateConfidence confidence,
            RuleCandidateSource source,
            SymbolEntity subjectSymbol,
            String groupId,
            String title,
            String description,
            String fingerprint,
            BigDecimal score,
            Integer supportCount,
            Boolean publicApiRelated,
            JsonNode summary,
            JsonNode impact,
            JsonNode meta
    ) {
        return new RuleCandidate(
                null,
                run,
                ruleKey,
                candidateKind == null ? RuleCandidateKind.GENERIC_PATTERN : candidateKind,
                confidence == null ? RuleCandidateConfidence.LOW : confidence,
                RuleCandidateStatus.CANDIDATE,
                source == null ? RuleCandidateSource.MIXED : source,
                subjectSymbol,
                groupId,
                title,
                description,
                fingerprint,
                score,
                supportCount == null ? 0 : supportCount,
                publicApiRelated != null && publicApiRelated,
                summary == null ? JsonNodeFactory.instance.objectNode() : summary,
                impact == null ? JsonNodeFactory.instance.objectNode() : impact,
                meta == null ? JsonNodeFactory.instance.objectNode() : meta
        );
    }

    public void updateMiningResult(
            RuleCandidateConfidence confidence,
            RuleCandidateSource source,
            String title,
            String description,
            BigDecimal score,
            Integer supportCount,
            Boolean publicApiRelated,
            JsonNode summary,
            JsonNode impact,
            JsonNode meta
    ) {
        if (confidence != null) {
            this.confidence = confidence;
        }
        if (source != null) {
            this.source = source;
        }
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        this.description = description;
        this.score = score;
        this.supportCount = supportCount == null ? 0 : supportCount;
        this.publicApiRelated = publicApiRelated != null && publicApiRelated;
        this.summary = summary == null ? JsonNodeFactory.instance.objectNode() : summary;
        this.impact = impact == null ? JsonNodeFactory.instance.objectNode() : impact;
        this.meta = meta == null ? JsonNodeFactory.instance.objectNode() : meta;
    }

    public void updateStatus(RuleCandidateStatus status) {
        if (status != null) {
            this.status = status;
        }
    }
}