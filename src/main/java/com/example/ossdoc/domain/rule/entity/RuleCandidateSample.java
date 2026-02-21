package com.example.ossdoc.domain.rule.entity;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "rule_candidate_sample")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RuleCandidateSample extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sample_id")
    private Long sampleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "rule_key", nullable = false)
    private String ruleKey;

    // (run_id, rule_key) -> summary FK
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "run_id", referencedColumnName = "run_id", insertable = false, updatable = false),
            @JoinColumn(name = "rule_key", referencedColumnName = "rule_key", insertable = false, updatable = false)
    })
    private RuleCandidateSummary summary;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "method_symbol_id", nullable = false)
    private SymbolEntity methodSymbol;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_type_symbol_id")
    private SymbolEntity ownerTypeSymbol;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_id", nullable = false)
    private Evidence evidence;
}