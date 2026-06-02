package com.example.ossdoc.domain.publicapi.support;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ENTRY_POINTS_JSON(라벨 없음)에 SUBSYSTEMS_JSON 기반 subsystem 라벨을 join한다.
 * PUBLICAPI 단계에서 ApiMapBuildService가 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntryPointSubsystemLabeler {

    private final ArtifactRepository artifactRepository;

    public List<EntryPointCandidate> label(String runId, List<EntryPointCandidate> candidates) {
        SubsystemMaps maps = loadSubsystemMaps(runId);
        if (maps.memberToSubsystem().isEmpty()) {
            log.debug("[PUBLICAPI] SUBSYSTEMS_JSON not found or empty. subsystem labels skipped. runId={}", runId);
            return candidates;
        }

        List<EntryPointCandidate> result = new ArrayList<>(candidates.size());
        for (EntryPointCandidate c : candidates) {
            String subsystemId    = maps.memberToSubsystem().get(c.getSymbolId());
            String subsystemLabel = subsystemId != null
                    ? maps.labelBySubsystem().get(subsystemId) : null;
            result.add(c.toBuilder()
                    .subsystemId(subsystemId)
                    .subsystemLabel(subsystemLabel)
                    .build());
        }
        return List.copyOf(result);
    }

    private SubsystemMaps loadSubsystemMaps(String runId) {
        Optional<Artifact> opt = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.SUBSYSTEMS_JSON);
        if (opt.isEmpty()) return new SubsystemMaps(Map.of(), Map.of());

        JsonNode meta       = opt.get().getMeta();
        JsonNode subsystems = meta.path("subsystems");
        if (!subsystems.isArray()) return new SubsystemMaps(Map.of(), Map.of());

        Map<String, String> memberToSubsystem = new HashMap<>();
        Map<String, String> labelBySubsystem  = new HashMap<>();

        for (JsonNode ss : subsystems) {
            String subsystemId = ss.path("subsystemId").asText(null);
            String name        = ss.path("name").asText(null);
            if (subsystemId == null) continue;
            if (name != null) labelBySubsystem.put(subsystemId, name);
            JsonNode members = ss.path("memberSymbolIds");
            if (members.isArray()) {
                for (JsonNode m : members) {
                    memberToSubsystem.put(m.asText(), subsystemId);
                }
            }
        }
        return new SubsystemMaps(memberToSubsystem, labelBySubsystem);
    }

    private record SubsystemMaps(
            Map<String, String> memberToSubsystem,
            Map<String, String> labelBySubsystem
    ) {}
}
