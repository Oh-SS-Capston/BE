package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.publicapi.dto.response.ApiMapBuildResponse;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.model.ExtensionPointCandidate;
import com.example.ossdoc.domain.publicapi.support.EntryPointJsonCodec;
import com.example.ossdoc.domain.publicapi.support.EntryPointSubsystemLabeler;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ArtifactRepository          artifactRepository;
    private final EntryPointJsonCodec         entryPointJsonCodec;
    private final EntryPointSubsystemLabeler  entryPointSubsystemLabeler;
    private final ObjectMapper                objectMapper;

    @Transactional
    public ApiMapBuildResponse build(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));

        publicApiEntrySyncService.ensureTypeEntries(run);

        // 정상 경로: ENTRY_POINTS_JSON(라벨 없음) 읽기 → subsystem 라벨 join
        // fallback: ENTRYPOINT 단계 실패/스킵 시 detect() 직접 호출 (이 시점에 SUBSYSTEMS_JSON이 존재하므로 라벨 포함)
        List<EntryPointCandidate> entryPoints = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.ENTRY_POINTS_JSON)
                .map(a -> entryPointSubsystemLabeler.label(
                        runId, entryPointJsonCodec.deserialize(a.getMeta())))
                .orElseGet(() -> {
                    log.warn("[PUBLICAPI] ENTRY_POINTS_JSON not found. falling back to detect(). runId={}", runId);
                    return entryPointDetectService.detect(runId);
                });

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
            /**
             * LLM 앵커 필드:
             * - owner_type_fqn: 클래스/메서드 설명 트리 병합 기준 키
             * - source_file/start_line/end_line: 코드 위치 기반 설명 생성의 최소 단위
             */
            putNullable(node, "owner_type_fqn", ep.getOwnerTypeFqn());
            putNullable(node, "source_file", ep.getSourceFile());
            putNullableInt(node, "start_line", ep.getStartLine());
            putNullableInt(node, "end_line", ep.getEndLine());
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
            putNullable(node, "owner_type_fqn", xp.getOwnerTypeFqn());
            putNullable(node, "source_file", xp.getSourceFile());
            putNullableInt(node, "start_line", xp.getStartLine());
            putNullableInt(node, "end_line", xp.getEndLine());
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
            node.put("qualified_name", ep.getQualifiedName());
            putNullable(node, "owner_type_fqn", ep.getOwnerTypeFqn());
            putNullable(node, "source_file", ep.getSourceFile());
            putNullableInt(node, "start_line", ep.getStartLine());
            putNullableInt(node, "end_line", ep.getEndLine());
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
            node.put("qualified_name", xp.getQualifiedName());
            putNullable(node, "owner_type_fqn", xp.getOwnerTypeFqn());
            putNullable(node, "source_file", xp.getSourceFile());
            putNullableInt(node, "start_line", xp.getStartLine());
            putNullableInt(node, "end_line", xp.getEndLine());
            node.put("simple_name", xp.getSimpleName());
            node.put("type_kind",  xp.getTypeKind());
            node.put("confidence", xp.getConfidence());
            ArrayNode sigs = node.putArray("signals");
            xp.getSignals().forEach(sigs::add);
            xpArray.add(node);
        }

        root.set("readme_mentions", buildReadmeMentionsJson(runId));

        return root;
    }

    /**
     * facts.json의 observations.readme_mentions 버킷에서 README에 언급된 공개 타입 목록을 추출한다.
     * - target_symbol: README에서 언급된 타입의 FQCN
     * - readme_file: 언급이 발견된 README 파일의 경로 (evidence에서 역추적)
     * - confidence: observation의 confidence_hint (README 신호는 0.6 고정)
     * 동일 FQCN이 복수 존재하면 마지막 항목으로 덮어쓴다 (중복 제거).
     */
    private ArrayNode buildReadmeMentionsJson(String runId) {
        ArrayNode out = objectMapper.createArrayNode();
        Artifact factsArtifact = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON)
                .orElse(null);
        if (factsArtifact == null || factsArtifact.getMeta() == null) {
            return out;
        }

        JsonNode meta = factsArtifact.getMeta();

        // evidence 배열에서 rawId → filePath 인덱스 구성 (README 타입만 포함)
        Map<String, String> readmePathByEvidenceId = new HashMap<>();
        JsonNode evidenceArray = meta.path("evidence");
        if (evidenceArray.isArray()) {
            for (JsonNode ev : evidenceArray) {
                if (!"README".equalsIgnoreCase(ev.path("type").asText(""))) {
                    continue;
                }
                String evId = ev.path("id").asText("");
                String path = ev.path("path").asText("");
                if (!evId.isBlank() && !path.isBlank()) {
                    readmePathByEvidenceId.put(evId, path);
                }
            }
        }

        // observations.readme_mentions 버킷 순회 — FQCN 기준 중복 제거
        JsonNode mentions = meta.path("observations").path("readme_mentions");
        if (!mentions.isArray()) {
            return out;
        }
        Map<String, ObjectNode> byFqn = new LinkedHashMap<>();
        for (JsonNode obs : mentions) {
            String qualifiedName = obs.path("target_symbol").asText("").trim();
            if (qualifiedName.isBlank()) {
                continue;
            }
            String simpleName = extractSimpleName(qualifiedName);
            double confidence = obs.path("confidence_hint").asDouble(0.6);

            // evidence_ids 첫 번째 항목에서 README 파일 경로 역추적
            String readmeFile = "";
            JsonNode evIds = obs.path("evidence_ids");
            if (evIds.isArray() && !evIds.isEmpty()) {
                String evId = evIds.get(0).asText("");
                readmeFile = readmePathByEvidenceId.getOrDefault(evId, "");
            }

            ObjectNode node = objectMapper.createObjectNode();
            node.put("qualified_name", qualifiedName);
            node.put("simple_name", simpleName);
            node.put("confidence", confidence);
            if (!readmeFile.isBlank()) {
                node.put("readme_file", readmeFile);
            }
            byFqn.put(qualifiedName, node);
        }

        byFqn.values().forEach(out::add);
        log.info("[PUBLICAPI] readme_mentions={} for runId={}", out.size(), runId);
        return out;
    }

    private String extractSimpleName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return "";
        }
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
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
        if (value == null || value.isBlank()) {
            node.putNull(key);
            return;
        }
        node.put(key, value);
    }

    private void putNullableInt(ObjectNode node, String key, Integer value) {
        if (value != null) {
            node.put(key, value);
        } else {
            node.putNull(key);
        }
    }
}
