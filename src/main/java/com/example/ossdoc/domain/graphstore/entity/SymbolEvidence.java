package com.example.ossdoc.domain.graphstore.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Table(name = "symbol_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SymbolEvidence {

    @EmbeddedId
    private SymbolEvidenceId id;

    @ManyToOne(fetch = LAZY, optional = false)
    @MapsId("symbolId")
    @JoinColumn(name = "symbol_id")
    private SymbolEntity symbol;

    @ManyToOne(fetch = LAZY, optional = false)
    @MapsId("evidenceId")
    @JoinColumn(name = "evidence_id")
    private Evidence evidence;
}
