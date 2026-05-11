package com.example.ossdoc.global.llm.entity;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * LLM 시나리오 결과를 run 단위로 캐시 저장한다.
 * - 향후 동일 run 재요청 시 캐시 재활용을 위한 기반 테이블이다.
 */
@Entity
@Table(
        name = "llm_scenario_cache",
        uniqueConstraints = @UniqueConstraint(name = "ux_llm_scenario_cache_run", columnNames = {"run_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LlmScenarioCache extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cache_id")
    private Long cacheId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion;

    @Column(name = "model", nullable = false, length = 120)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 40)
    private String promptVersion;

    @Column(name = "scenario_count", nullable = false)
    private Integer scenarioCount;

    @Column(name = "scenario_hash", nullable = false, length = 64)
    private String scenarioHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scenario_specs", nullable = false, columnDefinition = "jsonb")
    private JsonNode scenarioSpecs;

    /**
     * 동일 run 캐시가 이미 존재할 때 내용을 최신 결과로 갱신한다.
     */
    public void overwrite(
            String schemaVersion,
            String model,
            String promptVersion,
            Integer scenarioCount,
            String scenarioHash,
            JsonNode scenarioSpecs
    ) {
        this.schemaVersion = schemaVersion;
        this.model = model;
        this.promptVersion = promptVersion;
        this.scenarioCount = scenarioCount;
        this.scenarioHash = scenarioHash;
        this.scenarioSpecs = scenarioSpecs;
    }
}

