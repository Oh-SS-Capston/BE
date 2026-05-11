package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.model.ExtensionPointCandidate;
import com.example.ossdoc.domain.publicapi.repository.PublicApiEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExtensionPointDetectService {

    private static final int MIN_LINKED_INBOUND = 2;

    private final PublicApiEntryRepository publicApiEntryRepository;
    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactRepository artifactRepository;

    /**
     * 역할: public surface 내 extension point 후보를 판별하여 반환한다.
     *
     * 판별 기준:
     *  1. public_api_entry에 존재하는 타입
     *  2. is_abstract(modifiers에 "abstract" 포함) OR is_interface(IMPLEMENTS 인바운드 ≥ 1로 추론)
     *  3. linked inbound IMPLEMENTS + EXTENDS 합계 ≥ 2
     *
     * 주의: typeKind(interface/class/enum)는 graphstore 인제스트 시 유실되므로
     *       IMPLEMENTS 인바운드 존재 여부로 인터페이스를 추론한다.
     */
    public List<ExtensionPointCandidate> detect(String runId) {
        Set<String> publicSymbolIds = loadPublicSymbolIds(runId);
        if (publicSymbolIds.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> implementorCounts = countInboundEdges(runId, EdgeType.IMPLEMENTS);
        Map<String, Integer> extenderCounts    = countInboundEdges(runId, EdgeType.EXTENDS);

        SubsystemMaps subsystemMaps = loadSubsystemMaps(runId);

        List<SymbolEntity> typeSymbols = symbolRepository.findAllByRun_RunIdAndSymbolKind(runId, SymbolKind.TYPE);

        List<ExtensionPointCandidate> candidates = new ArrayList<>();
        for (SymbolEntity symbol : typeSymbols) {
            String symbolId = symbol.getSymbolId();
            if (!publicSymbolIds.contains(symbolId)) {
                continue;
            }

            int implementorCount = implementorCounts.getOrDefault(symbolId, 0);
            int extenderCount    = extenderCounts.getOrDefault(symbolId, 0);
            int linkedTotal      = implementorCount + extenderCount;

            if (linkedTotal < MIN_LINKED_INBOUND) {
                continue;
            }

            boolean isAbstract  = hasAbstractModifier(symbol.getModifiers());
            // IMPLEMENTS 인바운드가 있으면 피구현 타입 = 사실상 인터페이스
            boolean isInterface = implementorCount >= 1;

            if (!isAbstract && !isInterface) {
                continue;
            }

            String typeKind    = isInterface ? "interface" : "abstract";
            String subsystemId = subsystemMaps.memberToSubsystem().get(symbolId);
            String label       = subsystemId != null ? subsystemMaps.labelBySubsystem().get(subsystemId) : null;

            candidates.add(ExtensionPointCandidate.builder()
                    .symbolId(symbolId)
                    .qualifiedName(symbol.getQualifiedName())
                    .simpleName(symbol.getSimpleName())
                    .typeKind(typeKind)
                    .subsystemId(subsystemId)
                    .subsystemLabel(label)
                    .linkedImplementorCount(implementorCount)
                    .linkedExtenderCount(extenderCount)
                    .readmeMentioned(false)
                    .confidence(resolveConfidence(linkedTotal))
                    .build());
        }

        candidates.sort(Comparator.comparingInt(
                (ExtensionPointCandidate c) -> c.getLinkedImplementorCount() + c.getLinkedExtenderCount()
        ).reversed());

        return List.copyOf(candidates);
    }

    private Set<String> loadPublicSymbolIds(String runId) {
        List<PublicApiEntry> entries = publicApiEntryRepository.findAllByRun_RunId(runId);
        Set<String> ids = new HashSet<>(entries.size());
        for (PublicApiEntry entry : entries) {
            ids.add(entry.getSymbol().getSymbolId());
        }
        return ids;
    }

    private Map<String, Integer> countInboundEdges(String runId, EdgeType edgeType) {
        List<Edge> edges = edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, edgeType);
        Map<String, Integer> counts = new HashMap<>();
        for (Edge edge : edges) {
            String toId = edge.getToSymbol().getSymbolId();
            counts.merge(toId, 1, Integer::sum);
        }
        return counts;
    }

    private SubsystemMaps loadSubsystemMaps(String runId) {
        Optional<Artifact> artifact = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.SUBSYSTEMS_JSON);

        if (artifact.isEmpty()) {
            return new SubsystemMaps(Map.of(), Map.of());
        }

        JsonNode meta = artifact.get().getMeta();
        JsonNode subsystems = meta.path("subsystems");

        if (!subsystems.isArray()) {
            return new SubsystemMaps(Map.of(), Map.of());
        }

        Map<String, String> memberToSubsystem = new HashMap<>();
        Map<String, String> labelBySubsystem  = new HashMap<>();

        for (JsonNode ss : subsystems) {
            String subsystemId = ss.path("subsystemId").asText(null);
            String name        = ss.path("name").asText(null);
            if (subsystemId == null) {
                continue;
            }
            if (name != null) {
                labelBySubsystem.put(subsystemId, name);
            }
            JsonNode members = ss.path("memberSymbolIds");
            if (members.isArray()) {
                for (JsonNode m : members) {
                    memberToSubsystem.put(m.asText(), subsystemId);
                }
            }
        }

        return new SubsystemMaps(memberToSubsystem, labelBySubsystem);
    }

    private boolean hasAbstractModifier(JsonNode modifiers) {
        if (modifiers == null || !modifiers.isArray()) {
            return false;
        }
        for (JsonNode node : modifiers) {
            if ("abstract".equalsIgnoreCase(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private String resolveConfidence(int linkedTotal) {
        if (linkedTotal >= 3) return "HIGH";
        if (linkedTotal >= 2) return "MEDIUM";
        return "LOW";
    }

    private record SubsystemMaps(
            Map<String, String> memberToSubsystem,
            Map<String, String> labelBySubsystem
    ) {}
}
