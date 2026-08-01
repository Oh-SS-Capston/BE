package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "evidence",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_evidence_run_raw",
                columnNames = {"run_id", "raw_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Evidence extends BaseCreatedEntity {

    @Id
    @SequenceGenerator(
            name = "evidence_id_seq_gen",
            sequenceName = "evidence_evidence_id_seq",
            allocationSize = 50
    )
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "evidence_id_seq_gen"
    )
    @Column(name = "evidence_id")
    private Long evidenceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false)
    private EvidenceType evidenceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id")
    private FileIndex file;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "start_col")
    private Integer startCol;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "end_col")
    private Integer endCol;

    /**
     * Evidence가 직접 설명하는 owner symbol.
     * AST/ASM role별 Evidence를 조회할 때 사용한다.
     */
    @Column(name = "symbol", columnDefinition = "text")
    private String symbol;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;

    @Column(name = "hash")
    private String hash;

    /**
     * facts.json EvidenceFact.id.
     *
     * role이 다른 동일 snippet Evidence를 구분하는 canonical identity다.
     */
    @Column(name = "raw_id")
    private String rawId;

    /**
     * granularity, role, instruction_index, opcode,
     * annotation_name 등 추출기별 메타데이터.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb")
    private JsonNode attrs;
}
