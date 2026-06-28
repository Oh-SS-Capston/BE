package com.example.ossdoc.domain.publicapi.support;

import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.service.EntryPointDetectService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * EntryPointCandidate 목록을 ENTRY_POINTS_JSON artifact 형식으로 직렬화/역직렬화한다.
 * subsystem 라벨은 이 시점에 없으므로 포함하지 않는다 (EntryPointSubsystemLabeler 에서 join).
 */
@Component
@RequiredArgsConstructor
public class EntryPointJsonCodec {

    private final ObjectMapper objectMapper;

    public ObjectNode serialize(List<EntryPointCandidate> candidates, String runId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "1.0");
        root.put("run_id", runId);
        root.put("generated_at", Instant.now().toString());

        ArrayNode arr = root.putArray("candidates");
        for (EntryPointCandidate c : candidates) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("symbol_id",      c.getSymbolId());
            node.put("qualified_name", c.getQualifiedName());
            node.put("simple_name",    c.getSimpleName());
            node.put("type_kind",      c.getTypeKind());
            node.put("confidence",     c.getConfidence());
            node.put("role",           c.getRole());
            node.put("score",          c.getScore());
            if (c.getOwnerTypeFqn() != null) node.put("owner_type_fqn", c.getOwnerTypeFqn());
            if (c.getSourceFile()   != null) node.put("source_file",    c.getSourceFile());
            if (c.getStartLine()    != null) node.put("start_line",     c.getStartLine());
            if (c.getEndLine()      != null) node.put("end_line",       c.getEndLine());
            ArrayNode sigs = node.putArray("signals");
            if (c.getSignals() != null) c.getSignals().forEach(sigs::add);
            List<EntryPointCandidate.EntryMethodInfo> ems = c.getEntryMethods();
            if (ems != null && !ems.isEmpty()) {
                ArrayNode emArray = node.putArray("entry_methods");
                for (EntryPointCandidate.EntryMethodInfo em : ems) {
                    ObjectNode emNode = objectMapper.createObjectNode();
                    emNode.put("symbol_id", em.getSymbolId() != null ? em.getSymbolId() : "");
                    emNode.put("simple_name", em.getSimpleName() != null ? em.getSimpleName() : "");
                    emNode.put("reason", em.getReason() != null ? em.getReason() : "");
                    emArray.add(emNode);
                }
            }
            EntryPointCandidate.EvidenceCompleteness ec = c.getEvidenceCompleteness();
            if (ec != null) {
                ObjectNode ecNode = node.putObject("evidence_completeness");
                ecNode.put("source_available",      ec.isSourceAvailable());
                ecNode.put("javadoc_available",     ec.isJavadocAvailable());
                ecNode.put("annotations_available", ec.isAnnotationsAvailable());
                ecNode.put("build_mode",            ec.getBuildMode() != null ? ec.getBuildMode() : "UNKNOWN");
                ecNode.put("degraded",              ec.isDegraded());
            }
            arr.add(node);
        }
        return root;
    }

    public List<EntryPointCandidate> deserialize(JsonNode meta) {
        if (meta == null || !meta.has("candidates")) return List.of();
        JsonNode arr = meta.get("candidates");
        if (!arr.isArray()) return List.of();

        List<EntryPointCandidate> result = new ArrayList<>();
        for (JsonNode node : arr) {
            List<String> signals = new ArrayList<>();
            JsonNode sigArr = node.path("signals");
            if (sigArr.isArray()) {
                for (JsonNode s : sigArr) signals.add(s.asText());
            }
            List<EntryPointCandidate.EntryMethodInfo> entryMethods = new ArrayList<>();
            JsonNode emArr = node.path("entry_methods");
            if (emArr.isArray()) {
                for (JsonNode em : emArr) {
                    entryMethods.add(EntryPointCandidate.EntryMethodInfo.builder()
                            .symbolId(em.path("symbol_id").asText(""))
                            .simpleName(em.path("simple_name").asText(""))
                            .reason(em.path("reason").asText(""))
                            .build());
                }
            }
            EntryPointCandidate.EvidenceCompleteness ec = null;
            JsonNode ecNode = node.path("evidence_completeness");
            if (!ecNode.isMissingNode() && !ecNode.isNull()) {
                ec = EntryPointCandidate.EvidenceCompleteness.builder()
                        .sourceAvailable(     ecNode.path("source_available").asBoolean(false))
                        .javadocAvailable(    ecNode.path("javadoc_available").asBoolean(false))
                        .annotationsAvailable(ecNode.path("annotations_available").asBoolean(false))
                        .buildMode(           ecNode.path("build_mode").asText("UNKNOWN"))
                        .degraded(            ecNode.path("degraded").asBoolean(false))
                        .build();
            }
            result.add(EntryPointCandidate.builder()
                    .symbolId(           nullableText(node, "symbol_id"))
                    .qualifiedName(      nullableText(node, "qualified_name"))
                    .simpleName(         nullableText(node, "simple_name"))
                    .typeKind(           nullableText(node, "type_kind"))
                    .confidence(         nullableText(node, "confidence"))
                    .role(               nullableText(node, "role"))
                    .score(              node.path("score").asInt(0))
                    .ownerTypeFqn(       nullableText(node, "owner_type_fqn"))
                    .sourceFile(         nullableText(node, "source_file"))
                    .startLine(          node.has("start_line") ? node.path("start_line").asInt() : null)
                    .endLine(            node.has("end_line")   ? node.path("end_line").asInt()   : null)
                    .signals(            List.copyOf(signals))
                    .entryMethods(       List.copyOf(entryMethods))
                    .evidenceCompleteness(ec)
                    .build());
        }
        return List.copyOf(result);
    }

    /**
     * ENTRY_POINTS_JSON meta에서 minConfidence 이상인 symbolId만 추출한다.
     * cluster 단계에서 entryPoint 집합을 로드할 때 사용한다.
     */
    public Set<String> readSymbolIds(JsonNode meta, String minConfidence) {
        int minRank = EntryPointDetectService.confidenceRank(minConfidence);
        List<EntryPointCandidate> candidates = deserialize(meta);
        Set<String> ids = new HashSet<>();
        for (EntryPointCandidate c : candidates) {
            if ("EXAMPLE".equals(c.getRole())) {
                continue;
            }
            if (EntryPointDetectService.confidenceRank(c.getConfidence()) >= minRank) {
                ids.add(c.getSymbolId());
            }
        }
        return Set.copyOf(ids);
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) return null;
        return child.asText();
    }
}
