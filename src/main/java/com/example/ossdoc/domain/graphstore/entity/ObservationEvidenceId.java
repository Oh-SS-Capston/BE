package com.example.ossdoc.domain.graphstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class ObservationEvidenceId
        implements Serializable {

    @Column(name = "observation_id")
    private Long observationId;

    @Column(name = "evidence_id")
    private Long evidenceId;
}
