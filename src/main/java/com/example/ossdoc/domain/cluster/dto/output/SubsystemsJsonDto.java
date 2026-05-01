package com.example.ossdoc.domain.cluster.dto.output;

import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class SubsystemsJsonDto {
    private String schemaVersion;
    private String runId;
    private OffsetDateTime generatedAt;
    private Map<String, Object> algorithm;
    private List<Subsystem> subsystems;
}