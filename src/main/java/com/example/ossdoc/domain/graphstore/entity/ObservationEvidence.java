package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "observation_evidence",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_observation_evidence_order",
                columnNames = {
                        "observation_id",
                        "evidence_order"
                }
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ObservationEvidence
        extends BaseCreatedEntity {

    @EmbeddedId
    private ObservationEvidenceId id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @MapsId("observationId")
    @JoinColumn(
            name = "observation_id",
            nullable = false
    )
    private Observation observation;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @MapsId("evidenceId")
    @JoinColumn(
            name = "evidence_id",
            nullable = false
    )
    private Evidence evidence;

    /**
     * facts.json Observation.evidence_ids의 유효한 고유 Evidence 순서.
     */
    @Column(
            name = "evidence_order",
            nullable = false
    )
    private Integer evidenceOrder;
}
