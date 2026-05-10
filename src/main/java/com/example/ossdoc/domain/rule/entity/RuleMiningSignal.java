package com.example.ossdoc.domain.rule.entity;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.rule.enums.RuleCandidateSource;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
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
        name = "rule_mining_signal",
        indexes = {
                @Index(name = "idx_rule_signal_run", columnList = "run_id"),
                @Index(name = "idx_rule_signal_run_type", columnList = "run_id, signal_type"),
                @Index(name = "idx_rule_signal_symbol", columnList = "symbol_id"),
                @Index(name = "idx_rule_signal_edge", columnList = "edge_id"),
                @Index(name = "idx_rule_signal_evidence", columnList = "evidence_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RuleMiningSignal extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "signal_id")
    private Long signalId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 50)
    private RuleMiningSignalType signalType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 50)
    private RuleCandidateSource source;

    /**
     * 신호의 주체 심볼.
     * 보통 METHOD_CALL이면 호출 주체 메서드, IF/THROW/RETURN이면 snippet이 속한 메서드 심볼을 넣는다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "symbol_id")
    private SymbolEntity symbol;

    /**
     * GraphStore Edge 기반 신호일 경우 원본 edge.
     * 예: CALLS, THROWS, ACCESSES_FIELD, ANNOTATED_WITH.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edge_id")
    private Edge edge;

    /**
     * GraphStore Evidence 또는 snippet 휴리스틱의 근거.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_id")
    private Evidence evidence;

    /**
     * miner가 비교하기 쉬운 정규화 텍스트.
     * 예: save, delete, requireNonNull, throw IllegalArgumentException.
     */
    @Column(name = "normalized_text", length = 1000)
    private String normalizedText;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "confidence_hint", precision = 5, scale = 4)
    private BigDecimal confidenceHint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", nullable = false, columnDefinition = "jsonb")
    private JsonNode meta = JsonNodeFactory.instance.objectNode();

    public static RuleMiningSignal of(
            RepoRun run,
            RuleMiningSignalType signalType,
            RuleCandidateSource source,
            SymbolEntity symbol,
            Edge edge,
            Evidence evidence,
            String normalizedText,
            String snippet,
            Integer startLine,
            Integer endLine,
            BigDecimal confidenceHint,
            JsonNode meta
    ) {
        return new RuleMiningSignal(
                null,
                run,
                signalType,
                source == null ? RuleCandidateSource.MIXED : source,
                symbol,
                edge,
                evidence,
                normalizedText,
                snippet,
                startLine,
                endLine,
                confidenceHint,
                meta == null ? JsonNodeFactory.instance.objectNode() : meta
        );
    }
}