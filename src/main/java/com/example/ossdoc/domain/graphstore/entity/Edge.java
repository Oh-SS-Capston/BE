package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.domain.graphstore.enums.*;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import lombok.AccessLevel;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "edge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Edge extends BaseAuditedEntity {

    @Id
    @SequenceGenerator(
            name = "edge_id_seq_gen",
            sequenceName = "edge_edge_id_seq",
            allocationSize = 50
    )
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "edge_id_seq_gen"
    )
    @Column(name = "edge_id")
    private Long edgeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Enumerated(EnumType.STRING)
    @Column(name = "edge_type", nullable = false)
    private EdgeType edgeType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_symbol_id", nullable = false)
    private SymbolEntity fromSymbol;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_symbol_id")
    private SymbolEntity toSymbol;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "to_raw_ref", columnDefinition = "jsonb")
    private JsonNode toRawRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    private OriginKind origin = OriginKind.AST;

    @Enumerated(EnumType.STRING)
    @Column(name = "derivation_kind")
    private DerivationKind derivationKind = DerivationKind.DIRECT;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false)
    private ResolutionStatus resolution = ResolutionStatus.RESOLVED;

    @Column(name = "resolution_reason", columnDefinition = "text")
    private String resolutionReason;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb")
    private JsonNode attrs;
}