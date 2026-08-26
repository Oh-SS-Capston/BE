package com.example.ossdoc.domain.artifact.entity;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 50)
    private ArtifactKind kind;

    @Column(name = "schema_version")
    private String schemaVersion;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "path", nullable = false)
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", nullable = false, columnDefinition = "jsonb")
    private JsonNode meta;

    /**
     * 동일 산출물의 재실행 결과를 덮어쓸 때 메타데이터를 갱신한다.
     */
    public void overwriteJsonContent(String schemaVersion, String contentType, String path, JsonNode meta) {
        this.schemaVersion = schemaVersion;
        this.contentType = contentType;
        this.path = path;
        this.meta = meta;
    }
}
