package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.converter.FactsEdgeConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsEvidenceConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsSymbolConverter;
import com.example.ossdoc.domain.graphstore.dto.request.GraphStoreIngestRequest;
import com.example.ossdoc.domain.graphstore.dto.response.GraphStoreIngestResponse;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidenceId;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.exception.GraphStoreException;
import com.example.ossdoc.domain.graphstore.exception.code.GraphStoreErrorCode;
import com.example.ossdoc.domain.graphstore.repository.EdgeEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.EvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GraphStoreIngestService {

    private final RepoRunRepository repoRunRepository;
    private final ArtifactRepository artifactRepository;

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final EvidenceRepository evidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;

    private final FactsEvidenceConverter factsEvidenceConverter;
    private final FactsSymbolConverter factsSymbolConverter;
    private final FactsEdgeConverter factsEdgeConverter;
    private final SymbolIdGenerator symbolIdGenerator;
    private final GraphStoreFactsNormalizer graphStoreFactsNormalizer;

    private final ObjectMapper objectMapper;

    @Transactional
    public GraphStoreIngestResponse ingest(GraphStoreIngestRequest request, Long userId) {
        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new GraphStoreException(GraphStoreErrorCode.RUN_NOT_FOUND));

        if (run.getOwner() == null || !run.getOwner().getId().equals(userId)) {
            throw new GraphStoreException(GraphStoreErrorCode.RUN_ACCESS_DENIED);
        }

        Artifact factsArtifact = resolveFactsArtifact(request);

        JsonNode root = factsArtifact.getMeta();
        if (root == null || root.isNull()) {
            throw new GraphStoreException(GraphStoreErrorCode.FACTS_READ_FAILED);
        }

        RawFactsDocumentDto rawFacts;
        try {
            rawFacts = objectMapper.treeToValue(root, RawFactsDocumentDto.class);
        } catch (Exception e) {
            throw new GraphStoreException(GraphStoreErrorCode.INVALID_FACTS_SCHEMA);
        }

        NormalizedFactsDocument facts = graphStoreFactsNormalizer.normalize(rawFacts);
        validateFacts(facts);

        EvidenceSaveResult evidenceSaveResult = saveEvidence(run, facts);
        SymbolSaveResult symbolSaveResult = saveSymbols(run, facts, evidenceSaveResult.evidenceMap());
        EdgeSaveResult edgeSaveResult = saveEdges(run, facts, symbolSaveResult.symbolMap(), evidenceSaveResult.evidenceMap());

        return GraphStoreIngestResponse.builder()
                .runId(run.getRunId())
                .artifactId(factsArtifact.getArtifactId())
                .evidencesSaved(evidenceSaveResult.savedCount())
                .symbolsSaved(symbolSaveResult.savedCount())
                .edgesSaved(edgeSaveResult.edgesSaved())
                .edgeEvidenceSaved(edgeSaveResult.edgeEvidenceSaved())
                .skippedRelations(edgeSaveResult.skippedRelations())
                .build();
    }

    private Artifact resolveFactsArtifact(GraphStoreIngestRequest request) {
        if (request.getArtifactId() != null) {
            return artifactRepository.findById(request.getArtifactId())
                    .orElseThrow(() -> new GraphStoreException(GraphStoreErrorCode.FACTS_ARTIFACT_NOT_FOUND));
        }

        return artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(request.getRunId(), ArtifactKind.FACTS_JSON)
                .orElseThrow(() -> new GraphStoreException(GraphStoreErrorCode.FACTS_ARTIFACT_NOT_FOUND));
    }

    private void validateFacts(NormalizedFactsDocument facts) {
        if (facts == null ||
                facts.schemaVersion() == null ||
                facts.symbols() == null ||
                facts.relations() == null) {
            throw new GraphStoreException(GraphStoreErrorCode.INVALID_FACTS_SCHEMA);
        }
    }

    private EvidenceSaveResult saveEvidence(RepoRun run, NormalizedFactsDocument facts) {
        Map<String, Evidence> evidenceMap = new LinkedHashMap<>();
        int savedCount = 0;

        if (facts.evidence() == null || facts.evidence().isEmpty()) {
            return new EvidenceSaveResult(evidenceMap, savedCount);
        }

        for (Map.Entry<String, NormalizedEvidenceFact> entry : facts.evidence().entrySet()) {
            String factEvidenceId = entry.getKey();
            NormalizedEvidenceFact dto = entry.getValue();

            Evidence candidate = factsEvidenceConverter.toEntity(run, dto);
            Evidence existing = findExistingEvidence(run, candidate);

            Evidence resolved;
            if (existing != null) {
                resolved = existing;
            } else {
                resolved = evidenceRepository.save(candidate);
                savedCount++;
            }

            evidenceMap.put(factEvidenceId, resolved);
        }

        return new EvidenceSaveResult(evidenceMap, savedCount);
    }

    private Evidence findExistingEvidence(RepoRun run, Evidence candidate) {
        if (candidate.getHash() != null && !candidate.getHash().isBlank()) {
            return evidenceRepository.findFirstByRun_RunIdAndHash(run.getRunId(), candidate.getHash())
                    .orElse(null);
        }

        List<Evidence> matches = evidenceRepository.findByRun_RunIdAndEvidenceTypeAndStartLineAndEndLineAndSnippet(
                run.getRunId(),
                candidate.getEvidenceType(),
                candidate.getStartLine(),
                candidate.getEndLine(),
                candidate.getSnippet()
        );
        return matches.isEmpty() ? null : matches.get(0);
    }

    private SymbolSaveResult saveSymbols(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, Evidence> evidenceMap
    ) {
        Map<String, SymbolEntity> symbolMap = new LinkedHashMap<>();
        int savedCount = 0;

        for (NormalizedSymbolFact dto : facts.symbols()) {
            if (dto.symbol() == null || dto.symbol().isBlank()) {
                continue;
            }

            SymbolEntity existing = symbolRepository.findByRunIdAndQualifiedName(run.getRunId(), dto.symbol())
                    .orElse(null);

            SymbolEntity symbol;
            if (existing != null) {
                symbol = existing;
            } else {
                String symbolId = symbolIdGenerator.generate(run.getRunId(), dto.symbol());
                SymbolEntity entity = factsSymbolConverter.toEntity(symbolId, run, dto);
                symbol = symbolRepository.save(entity);
                savedCount++;
            }

            symbolMap.put(dto.symbol(), symbol);
        }

        for (NormalizedSymbolFact dto : facts.symbols()) {
            SymbolEntity current = symbolMap.get(dto.symbol());
            if (current == null) continue;

            if (dto.ownerTypeSymbol() != null && !dto.ownerTypeSymbol().isBlank()) {
                SymbolEntity owner = symbolMap.get(dto.ownerTypeSymbol());
                if (owner != null) {
                    current.assignOwner(owner);
                }
            }

            if (dto.evidenceIds() != null && !dto.evidenceIds().isEmpty()) {
                Evidence sourceEvidence = evidenceMap.get(dto.evidenceIds().get(0));
                if (sourceEvidence != null) {
                    current.assignSourceSpan(sourceEvidence.getStartLine(), sourceEvidence.getEndLine());
                }
            }
        }

        return new SymbolSaveResult(symbolMap, savedCount);
    }

    private EdgeSaveResult saveEdges(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, SymbolEntity> symbolMap,
            Map<String, Evidence> evidenceMap
    ) {
        int edgesSaved = 0;
        int edgeEvidenceSaved = 0;
        int skippedRelations = 0;

        for (NormalizedRelationFact dto : facts.relations()) {
            if (dto.srcSymbol() == null || dto.srcSymbol().isBlank()) {
                skippedRelations++;
                continue;
            }

            SymbolEntity from = symbolMap.get(dto.srcSymbol());
            if (from == null) {
                skippedRelations++;
                continue;
            }

            SymbolEntity to = null;
            if (dto.dstSymbol() != null && !dto.dstSymbol().isBlank()) {
                to = symbolMap.get(dto.dstSymbol());
            }

            Edge candidate = factsEdgeConverter.toEntity(run, dto, from, to);
            Edge edge = findExistingEdge(run, from, to, candidate);

            if (edge == null) {
                edge = edgeRepository.save(candidate);
                edgesSaved++;
            }

            if (dto.evidenceIds() != null) {
                for (String factEvidenceId : dto.evidenceIds()) {
                    Evidence evidence = evidenceMap.get(factEvidenceId);
                    if (evidence == null) continue;

                    EdgeEvidenceId edgeEvidenceId = new EdgeEvidenceId(edge.getEdgeId(), evidence.getEvidenceId());
                    if (edgeEvidenceRepository.existsById(edgeEvidenceId)) {
                        continue;
                    }

                    EdgeEvidence edgeEvidence = new EdgeEvidence(
                            edgeEvidenceId,
                            edge,
                            evidence
                    );
                    edgeEvidenceRepository.save(edgeEvidence);
                    edgeEvidenceSaved++;
                }
            }
        }

        return new EdgeSaveResult(edgesSaved, edgeEvidenceSaved, skippedRelations);
    }

    private Edge findExistingEdge(RepoRun run, SymbolEntity from, SymbolEntity to, Edge candidate) {
        if (to != null) {
            return edgeRepository.findFirstByRun_RunIdAndFromSymbol_SymbolIdAndEdgeTypeAndToSymbol_SymbolId(
                    run.getRunId(),
                    from.getSymbolId(),
                    candidate.getEdgeType(),
                    to.getSymbolId()
            ).orElse(null);
        }

        List<Edge> matches = edgeRepository.findByRun_RunIdAndFromSymbol_SymbolIdAndEdgeTypeAndToSymbolIsNull(
                run.getRunId(),
                from.getSymbolId(),
                candidate.getEdgeType()
        );

        String candidateRawRef = canonicalJson(candidate.getToRawRef());

        for (Edge existing : matches) {
            if (Objects.equals(candidateRawRef, canonicalJson(existing.getToRawRef()))) {
                return existing;
            }
        }

        return null;
    }

    private String canonicalJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private record EvidenceSaveResult(Map<String, Evidence> evidenceMap, int savedCount) {
    }

    private record SymbolSaveResult(Map<String, SymbolEntity> symbolMap, int savedCount) {
    }

    private record EdgeSaveResult(int edgesSaved, int edgeEvidenceSaved, int skippedRelations) {
    }
}