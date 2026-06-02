package com.example.ossdoc.domain.cluster.artifact;

import com.example.ossdoc.domain.cluster.artifact.output.SuperSubsystemsJson;
import com.example.ossdoc.domain.cluster.model.subsystem.SuperSubsystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuperSubsystemsJsonSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("빈 strategy로 SuperSubsystemsJson 직렬화/역직렬화 round-trip 성공")
    void emptyStrategy_roundTrip() throws Exception {
        SuperSubsystemsJson original = SuperSubsystemsJson.builder()
                .schemaVersion("1.0")
                .runId("run_test_001")
                .generatedAt(OffsetDateTime.parse("2026-05-30T00:00:00+09:00"))
                .strategy(SuperSubsystemsJson.Strategy.builder()
                        .name("MODULE_ID_MERGE")
                        .minSuperSize(10)
                        .fallback(Map.of("packageRootCommonPrefixDepth", 4))
                        .metrics(SuperSubsystemsJson.Metrics.builder()
                                .level1Count(0)
                                .level2Count(0)
                                .foldRatio(0.0)
                                .superMiscCount(0)
                                .avgDominantRatio(0.0)
                                .moduleIdCoverage(0.0)
                                .fallbackNodeCount(0)
                                .build())
                        .build())
                .superSubsystems(List.of())
                .build();

        String json = objectMapper.writeValueAsString(original);
        SuperSubsystemsJson restored = objectMapper.readValue(json, SuperSubsystemsJson.class);

        assertThat(restored.getSchemaVersion()).isEqualTo("1.0");
        assertThat(restored.getRunId()).isEqualTo("run_test_001");
        assertThat(restored.getSuperSubsystems()).isEmpty();
        assertThat(restored.getStrategy().getName()).isEqualTo("MODULE_ID_MERGE");
        assertThat(restored.getStrategy().getMinSuperSize()).isEqualTo(10);
        assertThat(restored.getStrategy().getMetrics().getLevel1Count()).isZero();
    }

    @Test
    @DisplayName("SuperSubsystem 멤버 포함 round-trip 성공")
    void withMembers_roundTrip() throws Exception {
        SuperSubsystem sup = SuperSubsystem.builder()
                .superSubsystemId("sup_001")
                .canonicalKey("junit-jupiter-api")
                .displayName("junit-jupiter-api")
                .memberSubsystemIds(List.of("ss_001", "ss_002"))
                .memberSymbolIds(List.of("sym_a", "sym_b", "sym_c"))
                .memberCount(3)
                .moduleAffinity(Map.of("junit-jupiter-api", 0.8, "junit-jupiter-engine", 0.2))
                .packageRoots(List.of("org.junit.jupiter.api"))
                .build();

        SuperSubsystemsJson original = SuperSubsystemsJson.builder()
                .schemaVersion("1.0")
                .runId("run_junit5")
                .generatedAt(OffsetDateTime.now())
                .strategy(SuperSubsystemsJson.Strategy.builder()
                        .name("MODULE_ID_MERGE")
                        .minSuperSize(10)
                        .fallback(Map.of())
                        .metrics(SuperSubsystemsJson.Metrics.builder()
                                .level1Count(103)
                                .level2Count(17)
                                .foldRatio(6.06)
                                .superMiscCount(2)
                                .avgDominantRatio(0.83)
                                .moduleIdCoverage(0.98)
                                .fallbackNodeCount(14)
                                .build())
                        .build())
                .superSubsystems(List.of(sup))
                .build();

        String json = objectMapper.writeValueAsString(original);
        SuperSubsystemsJson restored = objectMapper.readValue(json, SuperSubsystemsJson.class);

        assertThat(restored.getSuperSubsystems()).hasSize(1);
        SuperSubsystem restoredSup = restored.getSuperSubsystems().get(0);
        assertThat(restoredSup.getSuperSubsystemId()).isEqualTo("sup_001");
        assertThat(restoredSup.getCanonicalKey()).isEqualTo("junit-jupiter-api");
        assertThat(restoredSup.getMemberCount()).isEqualTo(3);
        assertThat(restoredSup.getModuleAffinity()).containsEntry("junit-jupiter-api", 0.8);
        assertThat(restoredSup.getMemberSubsystemIds()).containsExactly("ss_001", "ss_002");

        assertThat(restored.getStrategy().getMetrics().getFoldRatio()).isEqualTo(6.06);
        assertThat(restored.getStrategy().getMetrics().getModuleIdCoverage()).isEqualTo(0.98);
    }
}
