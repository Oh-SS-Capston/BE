package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactContentReader;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.graphstore.converter.FactsEdgeConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsEvidenceConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsSymbolConverter;
import com.example.ossdoc.domain.graphstore.dto.GraphStoreIngestRequest;
import com.example.ossdoc.domain.graphstore.dto.GraphStoreIngestResponse;
import com.example.ossdoc.domain.graphstore.dto.facts.EvidenceFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.FactsDocumentDto;
import com.example.ossdoc.domain.graphstore.dto.facts.RelationFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.SymbolFactDto;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GraphStoreIngestService {

    private final RepoRunRepository repoRunRepository;
    private final ArtifactService artifactService;
    private final ArtifactContentReader artifactContentReader;

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final EvidenceRepository evidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;

    private final FactsEvidenceConverter factsEvidenceConverter;
    private final FactsSymbolConverter factsSymbolConverter;
    private final FactsEdgeConverter factsEdgeConverter;
    private final SymbolIdGenerator symbolIdGenerator;

    private final ObjectMapper objectMapper;

    @Transactional
    public GraphStoreIngestResponse ingest(GraphStoreIngestRequest request, Long userId) {
        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new GraphStoreException(GraphStoreErrorCode.RUN_NOT_FOUND));

        if (run.getOwner() == null || !run.getOwner().getId().equals(userId)) {
            throw new GraphStoreException(GraphStoreErrorCode.RUN_ACCESS_DENIED);
        }

        Artifact factsArtifact = resolveFactsArtifact(request);
        JsonNode root = artifactContentReader.readJson(factsArtifact);

        FactsDocumentDto facts;
        try {
            facts = objectMapper.treeToValue(root, FactsDocumentDto.class);
        } catch (Exception e) {
            throw new GraphStoreException(GraphStoreErrorCode.INVALID_FACTS_SCHEMA);
        }

        validateFacts(facts);

        Map<String, Evidence> evidenceMap = saveEvidence(run, facts);
        Map<String, SymbolEntity> symbolMap = saveSymbols(run, facts, evidenceMap);

        EdgeSaveResult edgeSaveResult = saveEdges(run, facts, symbolMap, evidenceMap);

        return GraphStoreIngestResponse.builder()
                .runId(run.getRunId())
                .artifactId(factsArtifact.getArtifactId())
                .evidencesSaved(evidenceMap.size())
                .symbolsSaved(symbolMap.size())
                .edgesSaved(edgeSaveResult.edgesSaved)
                .edgeEvidenceSaved(edgeSaveResult.edgeEvidenceSaved)
                .skippedRelations(edgeSaveResult.skippedRelations)
                .build();
    }

    private Artifact resolveFactsArtifact(GraphStoreIngestRequest request) {
        Artifact artifact;
        if (request.getArtifactId() != null) {
            artifact = artifactService.getArtifact(request.getArtifactId());
        } else {
            artifact = artifactService.getLatestArtifact(request.getRunId(), ArtifactKind.FACTS_JSON);
        }

        if (artifact == null) {
            throw new GraphStoreException(GraphStoreErrorCode.FACTS_ARTIFACT_NOT_FOUND);
        }
        return artifact;
    }

    private void validateFacts(FactsDocumentDto facts) {
        if (facts == null ||
                facts.getSchemaVersion() == null ||
                facts.getSymbols() == null ||
                facts.getRelations() == null) {
            throw new GraphStoreException(GraphStoreErrorCode.INVALID_FACTS_SCHEMA);
        }
    }

    private Map<String, Evidence> saveEvidence(RepoRun run, FactsDocumentDto facts) {
        Map<String, Evidence> evidenceMap = new LinkedHashMap<>();

        if (facts.getEvidence() == null) return evidenceMap;

        for (Map.Entry<String, EvidenceFactDto> entry : facts.getEvidence().entrySet()) {
            String factEvidenceId = entry.getKey();
            EvidenceFactDto dto = entry.getValue();

            Evidence saved = evidenceRepository.save(
                    factsEvidenceConverter.toEntity(run, dto)
            );
            evidenceMap.put(factEvidenceId, saved);
        }

        return evidenceMap;
    }

    private Map<String, SymbolEntity> saveSymbols(RepoRun run,
                                                  FactsDocumentDto facts,
                                                  Map<String, Evidence> evidenceMap) {
        Map<String, SymbolEntity> symbolMap = new LinkedHashMap<>();

        for (SymbolFactDto dto : facts.getSymbols()) {
            if (dto.getSymbol() == null || dto.getSymbol().isBlank()) {
                continue;
            }

            SymbolEntity symbol = symbolRepository.findByRun_RunIdAndQualifiedName(run.getRunId(), dto.getSymbol())
                    .orElseGet(() -> {
                        String symbolId = symbolIdGenerator.generate(run.getRunId(), dto.getSymbol());
                        SymbolEntity entity = factsSymbolConverter.toEntity(symbolId, run, dto);
                        return symbolRepository.save(entity);
                    });

            symbolMap.put(dto.getSymbol(), symbol);
        }

        // owner / source span 2차 연결
        for (SymbolFactDto dto : facts.getSymbols()) {
            SymbolEntity current = symbolMap.get(dto.getSymbol());
            if (current == null) continue;

            if (dto.getOwnerTypeSymbol() != null) {
                SymbolEntity owner = symbolMap.get(dto.getOwnerTypeSymbol());
                if (owner != null) {
                    current.assignOwner(owner);
                }
            }

            if (dto.getEvidenceIds() != null && !dto.getEvidenceIds().isEmpty()) {
                Evidence sourceEvidence = evidenceMap.get(dto.getEvidenceIds().get(0));
                if (sourceEvidence != null) {
                    current.assignSourceSpan(sourceEvidence.getStartLine(), sourceEvidence.getEndLine());
                }
            }
        }

        return symbolMap;
    }

    private EdgeSaveResult saveEdges(RepoRun run,
                                     FactsDocumentDto facts,
                                     Map<String, SymbolEntity> symbolMap,
                                     Map<String, Evidence> evidenceMap) {
        int edgesSaved = 0;
        int edgeEvidenceSaved = 0;
        int skippedRelations = 0;

        for (RelationFactDto dto : facts.getRelations()) {
            if (dto.getSrcSymbol() == null || dto.getSrcSymbol().isBlank()) {
                skippedRelations++;
                continue;
            }

            SymbolEntity from = symbolMap.get(dto.getSrcSymbol());
            if (from == null) {
                skippedRelations++;
                continue;
            }

            SymbolEntity to = null;
            if (dto.getDstSymbol() != null) {
                to = symbolMap.get(dto.getDstSymbol());
            }

            Edge edge = edgeRepository.save(
                    factsEdgeConverter.toEntity(run, dto, from, to)
            );
            edgesSaved++;

            if (dto.getEvidenceIds() != null) {
                for (String factEvidenceId : dto.getEvidenceIds()) {
                    Evidence evidence = evidenceMap.get(factEvidenceId);
                    if (evidence == null) continue;

                    EdgeEvidence edgeEvidence = new EdgeEvidence(
                            new EdgeEvidenceId(edge.getEdgeId(), evidence.getEvidenceId()),
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

    private record EdgeSaveResult(int edgesSaved, int edgeEvidenceSaved, int skippedRelations) {
    }
}