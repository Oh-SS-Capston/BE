package com.example.ossdoc.domain.rule.entity;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(
        name = "rule_candidate_evidence",
        indexes = {
                @Index(name = "idx_rule_candidate_evidence_candidate", columnList = "candidate_id"),
                @Index(name = "idx_rule_candidate_evidence_signal", columnList = "signal_id"),
                @Index(name = "idx_rule_candidate_evidence_evidence", columnList = "evidence_id"),
                @Index(name = "idx_rule_candidate_evidence_edge", columnList = "edge_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RuleCandidateEvidence extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "candidate_evidence_id")
    private Long candidateEvidenceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private RuleCandidate candidate;

    /**
     * 후보를 만들 때 사용된 원천 신호.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id")
    private RuleMiningSignal signal;

    /**
     * GraphStore evidence FK.
     * snippet 휴리스틱 기반이어도 가능하면 연결한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_id")
    private Evidence evidence;

    /**
     * GraphStore edge FK.
     * CALLS, THROWS, ACCESSES_FIELD, ANNOTATED_WITH 기반 후보일 때 연결한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edge_id")
    private Edge edge;

    /**
     * 후보 내 근거 역할.
     * 예: IF_CONDITION, THROW_STATEMENT, METHOD_CALL, SUPPORTING.
     */
    @Column(name = "role", nullable = false, length = 50)
    private String role;

    @Column(name = "weight", precision = 5, scale = 4)
    private BigDecimal weight;

    /**
     * evidence가 나중에 변경되거나 lazy loading 없이 artifact를 만들 때 쓰기 위한 스냅샷 필드.
     */
    @Column(name = "file_path", length = 1000)
    private String filePath;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;

    @Column(name = "note", length = 1000)
    private String note;

    public static RuleCandidateEvidence of(
            RuleCandidate candidate,
            RuleMiningSignal signal,
            Evidence evidence,
            Edge edge,
            String role,
            BigDecimal weight,
            String filePath,
            Integer startLine,
            Integer endLine,
            String snippet,
            String note
    ) {
        return new RuleCandidateEvidence(
                null,
                candidate,
                signal,
                evidence,
                edge,
                role == null || role.isBlank() ? "SUPPORTING" : role,
                weight,
                filePath,
                startLine,
                endLine,
                snippet,
                note
        );
    }
}