package com.example.ossdoc.domain.cluster.artifact.output;

import com.example.ossdoc.domain.cluster.model.subsystem.SuperSubsystem;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@Jacksonized
public class SuperSubsystemsJson {
    private String schemaVersion;
    private String runId;
    private OffsetDateTime generatedAt;
    private Strategy strategy;
    private List<SuperSubsystem> superSubsystems;

    @Getter
    @Builder
    @Jacksonized
    public static class Strategy {
        private String name;           // MODULE_ID_MERGE | META_LEIDEN | HYBRID
        private int minSuperSize;
        private Map<String, Object> fallback;
        private Metrics metrics;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class Metrics {
        private int level1Count;
        private int level2Count;
        private double foldRatio;
        private int superMiscCount;
        private double avgDominantRatio;
        private double moduleIdCoverage;
        private int fallbackNodeCount;
    }
}
