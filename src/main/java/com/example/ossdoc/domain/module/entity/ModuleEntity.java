package com.example.ossdoc.domain.module.entity;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
        import lombok.*;
        import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "module")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ModuleEntity extends BaseCreatedEntity {

    @Id
    @Column(name = "module_id", nullable = false)
    private String moduleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "build_type")
    private String buildType;

    @Column(name = "packaging")
    private String packaging;

    @Column(name = "java_version")
    private String javaVersion;

    @Column(name = "group_id")
    private String groupId;

    @Column(name = "artifact_id")
    private String artifactId;

    @Column(name = "version")
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_roots", nullable = false, columnDefinition = "jsonb")
    private JsonNode sourceRoots;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "test_roots", nullable = false, columnDefinition = "jsonb")
    private JsonNode testRoots;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_roots", nullable = false, columnDefinition = "jsonb")
    private JsonNode resourceRoots;
}
