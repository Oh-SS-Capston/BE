package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "edge_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class EdgeEvidence extends BaseCreatedEntity {

    @EmbeddedId
    private EdgeEvidenceId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("edgeId")
    @JoinColumn(name = "edge_id", nullable = false)
    private Edge edge;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("evidenceId")
    @JoinColumn(name = "evidence_id", nullable = false)
    private Evidence evidence;
}
