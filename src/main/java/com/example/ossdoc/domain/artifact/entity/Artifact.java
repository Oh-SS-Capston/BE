package com.example.ossdoc.domain.artifact.entity;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;


@Entity
@Table(
        name = "artifact",
        uniqueConstraints = @UniqueConstraint(name = "ux_artifact_run_kind_path", columnNames = {"run_id", "kind", "path"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Artifact extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "artifact_id")
    private Long artifactId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "schema_version")
    private String schemaVersion;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "path", nullable = false)
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", nullable = false, columnDefinition = "jsonb")
    private JsonNode meta;
}