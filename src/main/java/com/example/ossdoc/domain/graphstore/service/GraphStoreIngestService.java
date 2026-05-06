// 역할: facts.json 기반 정규화 결과를 graphstore 엔티티로 적재한다.
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
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.exception.GraphStoreException;
import com.example.ossdoc.domain.graphstore.exception.code.GraphStoreErrorCode;
import com.example.ossdoc.domain.graphstore.repository.EdgeEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.EvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.module.repository.FileIndexRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class GraphStoreIngestService {

    private final RepoRunRepository repoRunRepository;
    private final ArtifactRepository artifactRepository;

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final EvidenceRepository evidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final FileIndexRepository fileIndexRepository;

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
        if (facts.observationCount() > 0) {
            log.warn("[GRAPHSTORE] observations {}건이 감지되었지만 현재 ingest 대상에는 포함되지 않습니다. runId={}",
                    facts.observationCount(), run.getRunId());
        }

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
                .observationsDetected(facts.observationCount())
                .observationsIgnored(facts.observationCount())
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

    /**
     * evidence 섹션을 저장하고 facts evidence id와 DB 엔티티를 매핑한다.
     */
    private EvidenceSaveResult saveEvidence(RepoRun run, NormalizedFactsDocument facts) {
        Map<String, Evidence> evidenceMap = new LinkedHashMap<>();
        int savedCount = 0;

        if (facts.evidence() == null || facts.evidence().isEmpty()) {
            return new EvidenceSaveResult(evidenceMap, savedCount);
        }

        for (Map.Entry<String, NormalizedEvidenceFact> entry : facts.evidence().entrySet()) {
            String factEvidenceId = entry.getKey();
            NormalizedEvidenceFact dto = entry.getValue();

            FileIndex fileIndex = resolveFileIndex(run, dto.path());
            Evidence candidate = factsEvidenceConverter.toEntity(run, dto, fileIndex);
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

    /**
     * 동일한 evidence가 이미 저장되어 있는지 해시/파일/라인 기준으로 확인한다.
     */
    private Evidence findExistingEvidence(RepoRun run, Evidence candidate) {
        if (candidate.getHash() != null && !candidate.getHash().isBlank()) {
            return evidenceRepository.findFirstByRun_RunIdAndHash(run.getRunId(), candidate.getHash())
                    .orElse(null);
        }

        List<Evidence> matches;
        if (candidate.getFile() != null && candidate.getFile().getFileId() != null) {
            matches = evidenceRepository.findByRun_RunIdAndEvidenceTypeAndFile_FileIdAndStartLineAndEndLineAndSnippet(
                    run.getRunId(),
                    candidate.getEvidenceType(),
                    candidate.getFile().getFileId(),
                    candidate.getStartLine(),
                    candidate.getEndLine(),
                    candidate.getSnippet()
            );
        } else {
            matches = evidenceRepository.findByRun_RunIdAndEvidenceTypeAndStartLineAndEndLineAndSnippet(
                    run.getRunId(),
                    candidate.getEvidenceType(),
                    candidate.getStartLine(),
                    candidate.getEndLine(),
                    candidate.getSnippet()
            );
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * evidence 경로를 기준으로 file_index를 조회하거나 생성한다.
     */
    private FileIndex resolveFileIndex(RepoRun run, String rawPath) {
        String normalizedPath = normalizeEvidencePath(rawPath);
        if (normalizedPath == null) {
            return null;
        }

        return fileIndexRepository.findFirstByRun_RunIdAndPath(run.getRunId(), normalizedPath)
                .orElseGet(() -> fileIndexRepository.save(new FileIndex(
                        null,
                        run,
                        null,
                        normalizedPath,
                        detectFileType(normalizedPath),
                        null,
                        null
                )));
    }

    /**
     * evidence path를 저장용 표준 경로로 정규화한다.
     */
    private String normalizeEvidencePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replace('\\', '/');
    }

    /**
     * 경로 확장자를 기반으로 file_index.file_type 값을 결정한다.
     */
    private String detectFileType(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == path.length() - 1) {
            return "unknown";
        }
        return path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private SymbolSaveResult saveSymbols(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, Evidence> evidenceMap
    ) {
        Map<String, SymbolEntity> symbolMap = new LinkedHashMap<>();
        Map<String, FileIndex> sourceFileCache = new HashMap<>();
        int savedCount = 0;
        int sourceFileLinkedCount = 0;

        for (NormalizedSymbolFact dto : facts.symbols()) {
            if (dto.symbol() == null || dto.symbol().isBlank()) {
                continue;
            }

            SymbolEntity existing = symbolRepository.findByRun_RunIdAndQualifiedName(run.getRunId(), dto.symbol())
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

            FileIndex sourceFile = resolveSymbolSourceFile(run, dto, sourceFileCache);
            if (sourceFile != null) {
                symbol.assignSourceFile(sourceFile);
                sourceFileLinkedCount++;
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

        log.info(
                "[GRAPHSTORE] symbol source file linking summary. runId={}, linkedCount={}",
                run.getRunId(),
                sourceFileLinkedCount
        );

        return new SymbolSaveResult(symbolMap, savedCount);
    }

    /**
     * symbol fact sourceFile 경로를 file_index 엔티티로 연결한다.
     */
    private FileIndex resolveSymbolSourceFile(
            RepoRun run,
            NormalizedSymbolFact symbolFact,
            Map<String, FileIndex> sourceFileCache
    ) {
        if (symbolFact == null) {
            return null;
        }
        String normalizedPath = normalizeEvidencePath(symbolFact.sourceFile());
        if (normalizedPath == null) {
            return null;
        }
        FileIndex cached = sourceFileCache.get(normalizedPath);
        if (cached != null) {
            return cached;
        }
        FileIndex resolved = resolveFileIndex(run, normalizedPath);
        if (resolved != null) {
            sourceFileCache.put(normalizedPath, resolved);
        }
        return resolved;
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
        int resolvedByRawRef = 0;

        Map<String, SymbolEntity> typeLookupIndex = buildTypeLookupIndex(symbolMap);

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

            SymbolEntity to = resolveDestinationSymbol(dto, symbolMap, typeLookupIndex);
            if (to != null
                    && (dto.dstSymbol() == null || dto.dstSymbol().isBlank())
                    && dto.dstRawRef() != null
                    && !dto.dstRawRef().isBlank()) {
                resolvedByRawRef++;
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

        log.info(
                "[GRAPHSTORE] relation linking summary. runId={}, totalRelations={}, resolvedByRawRef={}, skippedRelations={}",
                run.getRunId(),
                facts.relations().size(),
                resolvedByRawRef,
                skippedRelations
        );

        return new EdgeSaveResult(edgesSaved, edgeEvidenceSaved, skippedRelations);
    }

    /**
     * relation 목적지 심볼을 우선 dst_symbol로 찾고, 없으면 dst_raw_ref 타입명을 기준으로 보조 연결한다.
     */
    private SymbolEntity resolveDestinationSymbol(
            NormalizedRelationFact relation,
            Map<String, SymbolEntity> symbolMap,
            Map<String, SymbolEntity> typeLookupIndex
    ) {
        if (relation.dstSymbol() != null && !relation.dstSymbol().isBlank()) {
            SymbolEntity direct = symbolMap.get(relation.dstSymbol());
            if (direct != null) {
                return direct;
            }
            SymbolEntity fallbackFromDstSymbol = typeLookupIndex.get(normalizeTypeRefForLookup(relation.dstSymbol()));
            if (fallbackFromDstSymbol != null) {
                return fallbackFromDstSymbol;
            }
        }

        if (relation.dstRawRef() == null || relation.dstRawRef().isBlank()) {
            return null;
        }

        for (String candidate : buildTypeLookupCandidates(relation.dstRawRef())) {
            SymbolEntity resolved = typeLookupIndex.get(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * TYPE 심볼을 여러 표기(접두어/중첩 클래스 표기)로 조회할 수 있도록 인덱스를 만든다.
     */
    private Map<String, SymbolEntity> buildTypeLookupIndex(Map<String, SymbolEntity> symbolMap) {
        Map<String, SymbolEntity> index = new HashMap<>();

        for (Map.Entry<String, SymbolEntity> entry : symbolMap.entrySet()) {
            SymbolEntity symbol = entry.getValue();
            if (symbol == null || symbol.getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }

            addTypeLookupKey(index, entry.getKey(), symbol);
            addTypeLookupKey(index, symbol.getQualifiedName(), symbol);
        }

        return index;
    }

    /**
     * 하나의 타입 문자열에서 조회 후보 키를 생성한다.
     */
    private List<String> buildTypeLookupCandidates(String raw) {
        String normalized = normalizeTypeRefForLookup(raw);
        if (normalized == null) {
            return List.of();
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);
        candidates.add(normalized.replace('$', '.'));
        candidates.add("type:" + normalized);
        candidates.add("type:" + normalized.replace('$', '.'));

        return List.copyOf(candidates);
    }

    /**
     * 타입 참조 문자열을 조회 가능한 공통 포맷으로 정규화한다.
     */
    private String normalizeTypeRefForLookup(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        value = value.replace('\\', '.').replace('/', '.');

        if (value.startsWith("type:")) {
            value = value.substring("type:".length());
        }

        if (value.startsWith("? extends ")) {
            value = value.substring("? extends ".length());
        } else if (value.startsWith("? super ")) {
            value = value.substring("? super ".length());
        } else if ("?".equals(value)) {
            return null;
        }

        value = stripTypeDecorations(value);
        value = stripGenericPart(value);

        while (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        if (value.endsWith("...")) {
            value = value.substring(0, value.length() - 3);
        }

        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * class/interface/enum 같은 선언 키워드가 붙은 타입 문자열을 제거한다.
     */
    private String stripTypeDecorations(String value) {
        String current = value.trim();
        for (String prefix : List.of("class ", "interface ", "enum ", "record ")) {
            if (current.startsWith(prefix)) {
                return current.substring(prefix.length()).trim();
            }
        }
        return current;
    }

    /**
     * 중첩 제네릭까지 고려해 <> 블록을 제거한다.
     */
    private String stripGenericPart(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') {
                depth++;
                continue;
            }
            if (c == '>') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0) {
                builder.append(c);
            }
        }
        return builder.toString().trim();
    }

    /**
     * lookup 인덱스에 안전하게 키를 추가한다.
     */
    private void addTypeLookupKey(Map<String, SymbolEntity> index, String rawKey, SymbolEntity symbol) {
        String normalized = normalizeTypeRefForLookup(rawKey);
        if (normalized == null) {
            return;
        }

        for (String candidate : buildTypeLookupCandidates(normalized)) {
            index.putIfAbsent(candidate, symbol);
        }
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
