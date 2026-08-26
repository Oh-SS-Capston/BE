package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.SEQUENCE;

@Entity
@Table(name = "observation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Observation extends BaseCreatedEntity {

    @Id
    @SequenceGenerator(name = "obs_id_seq", sequenceName = "observation_observation_id_seq", allocationSize = 50)
    @GeneratedValue(strategy = SEQUENCE, generator = "obs_id_seq")
    @Column(name = "observation_id")
    private Long observationId;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "site_symbol", columnDefinition = "text")
    private String siteSymbol;

    @Column(name = "target_symbol", columnDefinition = "text")
    private String targetSymbol;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_type_ref", columnDefinition = "jsonb")
    private JsonNode targetTypeRef;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "confidence_hint", precision = 5, scale = 4)
    private BigDecimal confidenceHint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb")
    private JsonNode attrs;
}
