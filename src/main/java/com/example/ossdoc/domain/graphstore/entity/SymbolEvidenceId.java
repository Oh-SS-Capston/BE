package com.example.ossdoc.domain.graphstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SymbolEvidenceId implements Serializable {

    @Column(name = "symbol_id")
    private String symbolId;

    @Column(name = "evidence_id")
    private Long evidenceId;
}
