package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.publicapi.dto.response.ApiMapBuildResponse;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.model.ExtensionPointCandidate;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiMapBuildService {

    private static final String SCHEMA_VERSION   = "1.0";
    private static final String API_SURFACE_FILE = "api_surface.json";
    private static final String API_MAP_FILE     = "api_map.json";

    private final RepoRunRepository           repoRunRepository;
    private final PublicApiEntrySyncService   publicApiEntrySyncService;
    private final EntryPointDetectService     entryPointDetectService;
    private final ExtensionPointDetectService extensionPointDetectService;
    private final ArtifactService             artifactService;
    private final ObjectMapper                objectMapper;

    @Transactional
    public ApiMapBuildResponse build(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));

        publicApiEntrySyncService.ensureTypeEntries(run);

        List<EntryPointCandidate>   entryPoints     = entryPointDetectService.detect(runId);
        List<ExtensionPointCandidate> extensionPoints = extensionPointDetectService.detect(runId);

        // Extension Point 우선 원칙: 동일 symbolId가 양쪽에 존재하면 entry_points에서 제거
        // HIGH confidence entry point는 예외 유지 (직접 진입점 신호가 충분히 강한 경우)
        List<EntryPointCandidate> dedupedEntryPoints = deduplicateEntryPoints(entryPoints, extensionPoints);

        log.info("[PUBLICAPI] runId={}, entryPoints={} (deduped from {}), extensionPoints={}",
                runId, dedupedEntryPoints.size(), entryPoints.size(), extensionPoints.size());

        String generatedAt = Instant.now().toString();

        ObjectNode apiSurfaceJson = buildApiSurfaceJson(dedupedEntryPoints, extensionPoints, runId, generatedAt);
        ObjectNode apiMapJson     = buildApiMapJson(dedupedEntryPoints, extensionPoints, runId, generatedAt);

        Artifact surfaceArtifact = artifactService.saveJsonArtifact(
                run, ArtifactKind.API_SURFACE_JSON, SCHEMA_VERSION, API_SURFACE_FILE, apiSurfaceJson);
        Artifact mapArtifact = artifactService.saveJsonArtifact(
                run, ArtifactKind.API_MAP_JSON, SCHEMA_VERSION, API_MAP_FILE, apiMapJson);

        return ApiMapBuildResponse.builder()
                .entryPointTotal(dedupedEntryPoints.size())
                .extensionPointTotal(extensionPoints.size())
                .primaryCount((int) dedupedEntryPoints.stream().filter(e -> "PRIMARY".equals(e.getRole())).count())
                .secondaryCount((int) dedupedEntryPoints.stream().filter(e -> "SECONDARY".equals(e.getRole())).count())
                .highConfidenceCount((int) countConfidence(dedupedEntryPoints, extensionPoints, "HIGH"))
                .medConfidenceCount((int) countConfidence(dedupedEntryPoints, extensionPoints, "MED"))
                .lowConfidenceCount((int) countConfidence(dedupedEntryPoints, extensionPoints, "LOW"))
                .apiSurfaceArtifactUrl(surfaceArtifact.getPath())
                .apiMapArtifactUrl(mapArtifact.getPath())
                .build();
    }

    // ── JSON builders ────────────────────────────────────────────────────────

    private ObjectNode buildApiSurfaceJson(
            List<EntryPointCandidate> entryPoints,
            List<ExtensionPointCandidate> extensionPoints,
            String runId,
            String generatedAt
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("run_id",         runId);
        root.put("generated_at",   generatedAt);

        ArrayNode epArray = root.putArray("entry_points");
        for (EntryPointCandidate ep : entryPoints) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("symbol_id",      ep.getSymbolId());
            node.put("qualified_name", ep.getQualifiedName());
            node.put("simple_name",    ep.getSimpleName());
            node.put("type_kind",      ep.getTypeKind());
            node.put("role",           ep.getRole());
            node.put("confidence",     ep.getConfidence());
            node.put("score",          ep.getScore());
            ArrayNode sigs = node.putArray("signals");
            ep.getSignals().forEach(sigs::add);
            putNullable(node, "subsystem_id",    ep.getSubsystemId());
            putNullable(node, "subsystem_label", ep.getSubsystemLabel());
            epArray.add(node);
        }

        ArrayNode xpArray = root.putArray("extension_points");
        for (ExtensionPointCandidate xp : extensionPoints) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("symbol_id",               xp.getSymbolId());
            node.put("qualified_name",           xp.getQualifiedName());
            node.put("simple_name",              xp.getSimpleName());
            node.put("type_kind",                xp.getTypeKind());
            node.put("confidence",               xp.getConfidence());
            node.put("score",                    xp.getScore());
            node.put("linked_implementor_count", xp.getLinkedImplementorCount());
            node.put("linked_extender_count",    xp.getLinkedExtenderCount());
            ArrayNode sigs = node.putArray("signals");
            xp.getSignals().forEach(sigs::add);
            putNullable(node, "subsystem_id",    xp.getSubsystemId());
            putNullable(node, "subsystem_label", xp.getSubsystemLabel());
            xpArray.add(node);
        }

        return root;
    }

    private ObjectNode buildApiMapJson(
            List<EntryPointCandidate> entryPoints,
            List<ExtensionPointCandidate> extensionPoints,
            String runId,
            String generatedAt
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("run_id",         runId);
        root.put("generated_at",   generatedAt);

        ObjectNode stats = root.putObject("stats");
        stats.put("entry_point_total",     entryPoints.size());
        stats.put("extension_point_total", extensionPoints.size());
        stats.put("primary_count",   (int) entryPoints.stream().filter(e -> "PRIMARY".equals(e.getRole())).count());
        stats.put("secondary_count", (int) entryPoints.stream().filter(e -> "SECONDARY".equals(e.getRole())).count());
        stats.put("high_confidence_count", (int) countConfidence(entryPoints, extensionPoints, "HIGH"));
        stats.put("med_confidence_count",  (int) countConfidence(entryPoints, extensionPoints, "MED"));
        stats.put("low_confidence_count",  (int) countConfidence(entryPoints, extensionPoints, "LOW"));

        ArrayNode epArray = root.putArray("entry_points");
        for (EntryPointCandidate ep : entryPoints) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("symbol_id",  ep.getSymbolId());
            node.put("simple_name", ep.getSimpleName());
            node.put("type_kind",  ep.getTypeKind());
            node.put("role",       ep.getRole());
            node.put("confidence", ep.getConfidence());
            ArrayNode sigs = node.putArray("signals");
            ep.getSignals().forEach(sigs::add);
            epArray.add(node);
        }

        ArrayNode xpArray = root.putArray("extension_points");
        for (ExtensionPointCandidate xp : extensionPoints) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("symbol_id",  xp.getSymbolId());
            node.put("simple_name", xp.getSimpleName());
            node.put("type_kind",  xp.getTypeKind());
            node.put("confidence", xp.getConfidence());
            ArrayNode sigs = node.putArray("signals");
            xp.getSignals().forEach(sigs::add);
            xpArray.add(node);
        }

        return root;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Extension Point 우선 원칙: 동일 symbolId가 entry_points와 extension_points 양쪽에 존재할 때
     * entry_points에서 제거한다. HIGH confidence entry point는 직접 진입점 신호가 충분하므로 예외 유지.
     */
    private List<EntryPointCandidate> deduplicateEntryPoints(
            List<EntryPointCandidate> entryPoints,
            List<ExtensionPointCandidate> extensionPoints) {

        Set<String> extensionSymbolIds = extensionPoints.stream()
                .map(ExtensionPointCandidate::getSymbolId)
                .collect(Collectors.toSet());

        return entryPoints.stream()
                .filter(ep -> !extensionSymbolIds.contains(ep.getSymbolId())
                           || "HIGH".equals(ep.getConfidence()))
                .collect(Collectors.toList());
    }

    private long countConfidence(
            List<EntryPointCandidate> entryPoints,
            List<ExtensionPointCandidate> extensionPoints,
            String level
    ) {
        return entryPoints.stream().filter(e -> level.equals(e.getConfidence())).count()
             + extensionPoints.stream().filter(e -> level.equals(e.getConfidence())).count();
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        } else {
            node.putNull(key);
        }
    }
}
