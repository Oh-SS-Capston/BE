package com.example.ossdoc.domain.cluster.artifact.output;

import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@Jacksonized
public class SubsystemsJson {
    private String schemaVersion;
    private String runId;
    private OffsetDateTime generatedAt;
    private Map<String, Object> algorithm;
    private List<Subsystem> subsystems;
}