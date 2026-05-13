package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "evidence",
        uniqueConstraints = @UniqueConstraint(name = "ux_evidence_run_raw", columnNames = {"run_id", "raw_id"})
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
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "evidence_id_seq_gen")
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

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;

    @Column(name = "hash")
    private String hash;

    @Column(name = "raw_id")
    private String rawId;
}
