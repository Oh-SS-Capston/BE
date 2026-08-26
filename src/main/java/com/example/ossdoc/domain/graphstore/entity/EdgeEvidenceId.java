package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class EdgeEvidenceId implements Serializable {

    @Column(name = "edge_id")
    private Long edgeId;

    @Column(name = "evidence_id")
    private Long evidenceId;
}