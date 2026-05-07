package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.converter.FactsEdgeConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsEvidenceConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsSymbolConverter;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.dto.request.GraphStoreIngestRequest;
import com.example.ossdoc.domain.graphstore.dto.response.GraphStoreIngestResponse;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidenceId;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.exception.GraphStoreException;
import com.example.ossdoc.domain.graphstore.exception.code.GraphStoreErrorCode;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class GraphStoreIngestService {

    private static final int BATCH_SIZE = 500;

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
            log.warn(
                    "[GRAPHSTORE] observations are currently ignored by ingest. count={}, runId={}",
                    facts.observationCount(),
                    run.getRunId()
            );
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
        if (facts == null || facts.schemaVersion() == null || facts.symbols() == null || facts.relations() == null) {
            throw new GraphStoreException(GraphStoreErrorCode.INVALID_FACTS_SCHEMA);
        }
    }

    /**
     * Evidence를 저장한다.
     * run 단위 선조회 인덱스를 사용해 건별 중복 조회(N+1)를 줄인다.
     */
    private EvidenceSaveResult saveEvidence(RepoRun run, NormalizedFactsDocument facts) {
        Map<String, Evidence> evidenceMap = new LinkedHashMap<>();
        int savedCount = 0;

        if (facts.evidence() == null || facts.evidence().isEmpty()) {
            return new EvidenceSaveResult(evidenceMap, savedCount);
        }

        Map<String, FileIndex> fileIndexCache = loadFileIndexCache(run);
        EvidenceLookupIndexes lookupIndexes = buildEvidenceLookupIndexes(run);

        for (Map.Entry<String, NormalizedEvidenceFact> entry : facts.evidence().entrySet()) {
            String factEvidenceId = entry.getKey();
            NormalizedEvidenceFact dto = entry.getValue();

            FileIndex fileIndex = resolveFileIndex(run, dto.path(), fileIndexCache);
            Evidence candidate = factsEvidenceConverter.toEntity(run, dto, fileIndex);
            Evidence existing = findEvidenceInLookup(lookupIndexes, candidate);

            Evidence resolved;
            if (existing != null) {
                resolved = existing;
            } else {
                resolved = evidenceRepository.save(candidate);
                registerEvidenceLookup(lookupIndexes, resolved);
                savedCount++;
            }

            evidenceMap.put(factEvidenceId, resolved);
        }

        return new EvidenceSaveResult(evidenceMap, savedCount);
    }

    /**
     * run 범위 기존 evidence를 한 번에 읽어 중복 판별용 인덱스를 만든다.
     */
    private EvidenceLookupIndexes buildEvidenceLookupIndexes(RepoRun run) {
        Map<String, Evidence> hashLookup = new HashMap<>();
        Map<EvidenceSignature, Evidence> fileSignatureLookup = new HashMap<>();
        Map<EvidenceSignature, Evidence> lineSignatureLookup = new HashMap<>();
        EvidenceLookupIndexes indexes = new EvidenceLookupIndexes(hashLookup, fileSignatureLookup, lineSignatureLookup);

        List<Evidence> existing = evidenceRepository.findAllByRun_RunId(run.getRunId());
        for (Evidence evidence : existing) {
            registerEvidenceLookup(indexes, evidence);
        }

        return indexes;
    }

    /**
     * 신규 evidence 후보를 인덱스에서 조회해 기존 데이터와 중복 여부를 확인한다.
     */
    private Evidence findEvidenceInLookup(EvidenceLookupIndexes indexes, Evidence candidate) {
        String hash = normalizeBlank(candidate.getHash());
        if (hash != null) {
            return indexes.hashLookup().get(hash);
        }

        EvidenceSignature signature = toEvidenceSignature(candidate);
        if (signature == null) {
            return null;
        }

        if (signature.fileId() != null) {
            return indexes.fileSignatureLookup().get(signature);
        }
        return indexes.lineSignatureLookup().get(signature);
    }

    /**
     * 저장 완료된 evidence를 중복 판별 인덱스에 등록한다.
     */
    private void registerEvidenceLookup(EvidenceLookupIndexes indexes, Evidence evidence) {
        if (evidence == null) {
            return;
        }

        String hash = normalizeBlank(evidence.getHash());
        if (hash != null) {
            indexes.hashLookup().putIfAbsent(hash, evidence);
            return;
        }

        EvidenceSignature signature = toEvidenceSignature(evidence);
        if (signature == null) {
            return;
        }

        if (signature.fileId() != null) {
            indexes.fileSignatureLookup().putIfAbsent(signature, evidence);
        } else {
            indexes.lineSignatureLookup().putIfAbsent(signature, evidence);
        }
    }

    private EvidenceSignature toEvidenceSignature(Evidence evidence) {
        if (evidence == null || evidence.getEvidenceType() == null) {
            return null;
        }

        Long fileId = evidence.getFile() == null ? null : evidence.getFile().getFileId();
        return new EvidenceSignature(
                evidence.getEvidenceType(),
                fileId,
                evidence.getStartLine(),
                evidence.getEndLine(),
                evidence.getSnippet()
        );
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * run 범위 file_index를 메모리 캐시로 적재한다.
     */
    private Map<String, FileIndex> loadFileIndexCache(RepoRun run) {
        Map<String, FileIndex> cache = new HashMap<>();
        List<FileIndex> existingFiles = fileIndexRepository.findAllByRun_RunId(run.getRunId());
        for (FileIndex fileIndex : existingFiles) {
            if (fileIndex.getPath() != null) {
                cache.put(fileIndex.getPath(), fileIndex);
            }
        }
        return cache;
    }

    /**
     * 경로 기준 file_index 조회/생성을 수행한다.
     * 캐시를 우선 사용해 반복 DB 조회를 줄인다.
     */
    private FileIndex resolveFileIndex(RepoRun run, String rawPath, Map<String, FileIndex> cache) {
        String normalizedPath = normalizeEvidencePath(rawPath);
        if (normalizedPath == null) {
            return null;
        }

        FileIndex existing = cache.get(normalizedPath);
        if (existing != null) {
            return existing;
        }

        FileIndex created = fileIndexRepository.save(new FileIndex(
                null,
                run,
                null,
                normalizedPath,
                detectFileType(normalizedPath),
                null,
                null
        ));
        cache.put(normalizedPath, created);
        return created;
    }

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

    private String detectFileType(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == path.length() - 1) {
            return "unknown";
        }
        return path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Symbol을 저장한다.
     * run 단위 선조회 캐시를 사용해 건별 symbol 조회 쿼리를 제거한다.
     */
    private SymbolSaveResult saveSymbols(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, Evidence> evidenceMap
    ) {
        Map<String, SymbolEntity> symbolMap = new LinkedHashMap<>();
        Map<String, SymbolEntity> existingSymbolsByQualifiedName = loadExistingSymbolsByQualifiedName(run);
        Map<String, FileIndex> sourceFileCache = loadFileIndexCache(run);
        int savedCount = 0;
        int sourceFileLinkedCount = 0;

        for (NormalizedSymbolFact dto : facts.symbols()) {
            if (dto.symbol() == null || dto.symbol().isBlank()) {
                continue;
            }

            SymbolEntity symbol = existingSymbolsByQualifiedName.get(dto.symbol());
            if (symbol == null) {
                String symbolId = symbolIdGenerator.generate(run.getRunId(), dto.symbol());
                SymbolEntity entity = factsSymbolConverter.toEntity(symbolId, run, dto);
                symbol = symbolRepository.save(entity);
                existingSymbolsByQualifiedName.put(dto.symbol(), symbol);
                savedCount++;
            }

            FileIndex sourceFile = resolveSymbolSourceFile(run, dto, sourceFileCache);
            if (shouldAssignSourceFile(symbol, sourceFile)) {
                symbol.assignSourceFile(sourceFile);
                sourceFileLinkedCount++;
            }

            symbolMap.put(dto.symbol(), symbol);
        }

        for (NormalizedSymbolFact dto : facts.symbols()) {
            SymbolEntity current = symbolMap.get(dto.symbol());
            if (current == null) {
                continue;
            }

            if (dto.ownerTypeSymbol() != null && !dto.ownerTypeSymbol().isBlank()) {
                SymbolEntity owner = symbolMap.get(dto.ownerTypeSymbol());
                if (owner != null && shouldAssignOwner(current, owner)) {
                    current.assignOwner(owner);
                }
            }

            if (dto.evidenceIds() != null && !dto.evidenceIds().isEmpty()) {
                Evidence sourceEvidence = evidenceMap.get(dto.evidenceIds().get(0));
                if (sourceEvidence != null && shouldAssignSourceSpan(current, sourceEvidence)) {
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
     * run 범위 symbol을 qualifiedName 기준 맵으로 구성한다.
     */
    private Map<String, SymbolEntity> loadExistingSymbolsByQualifiedName(RepoRun run) {
        Map<String, SymbolEntity> cache = new HashMap<>();
        List<SymbolEntity> existingSymbols = symbolRepository.findAllByRun_RunId(run.getRunId());
        for (SymbolEntity symbol : existingSymbols) {
            if (symbol.getQualifiedName() != null) {
                cache.putIfAbsent(symbol.getQualifiedName(), symbol);
            }
        }
        return cache;
    }

    private boolean shouldAssignSourceFile(SymbolEntity symbol, FileIndex sourceFile) {
        if (sourceFile == null) {
            return false;
        }
        if (symbol.getSourceFile() == null) {
            return true;
        }
        return !Objects.equals(symbol.getSourceFile().getFileId(), sourceFile.getFileId());
    }

    private boolean shouldAssignOwner(SymbolEntity current, SymbolEntity owner) {
        if (current.getOwner() == null) {
            return true;
        }
        return !Objects.equals(current.getOwner().getSymbolId(), owner.getSymbolId());
    }

    private boolean shouldAssignSourceSpan(SymbolEntity symbol, Evidence sourceEvidence) {
        return !Objects.equals(symbol.getSourceStartLine(), sourceEvidence.getStartLine())
                || !Objects.equals(symbol.getSourceEndLine(), sourceEvidence.getEndLine());
    }

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

        return resolveFileIndex(run, normalizedPath, sourceFileCache);
    }

    /**
     * Relation을 Edge/EdgeEvidence로 적재한다.
     * 기존 edge/edge_evidence를 선조회하여 중복 판별 후 배치 저장한다.
     */
    private EdgeSaveResult saveEdges(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, SymbolEntity> symbolMap,
            Map<String, Evidence> evidenceMap
    ) {
        int edgesSaved = 0;
        int skippedRelations = 0;
        int resolvedByRawRef = 0;

        Map<String, SymbolEntity> typeLookupIndex = buildTypeLookupIndex(symbolMap);

        List<Edge> existingEdges = edgeRepository.findAllByRun_RunId(run.getRunId());
        Map<EdgeKey, Edge> edgeLookup = new HashMap<>(Math.max(16, existingEdges.size() * 2));
        for (Edge existing : existingEdges) {
            edgeLookup.putIfAbsent(toEdgeKey(existing), existing);
        }

        Set<String> existingEdgeEvidenceKeys = loadExistingEdgeEvidenceKeys(existingEdges);
        List<Edge> newEdges = new ArrayList<>();
        List<PendingEdgeEvidence> pendingEdgeEvidence = new ArrayList<>();

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
            EdgeKey edgeKey = toEdgeKey(from, candidate);
            Edge edge = edgeLookup.get(edgeKey);

            if (edge == null) {
                edge = candidate;
                edgeLookup.put(edgeKey, edge);
                newEdges.add(edge);
                edgesSaved++;
            }

            if (dto.evidenceIds() != null) {
                for (String factEvidenceId : dto.evidenceIds()) {
                    Evidence evidence = evidenceMap.get(factEvidenceId);
                    if (evidence == null || evidence.getEvidenceId() == null) {
                        continue;
                    }
                    pendingEdgeEvidence.add(new PendingEdgeEvidence(edge, evidence));
                }
            }
        }

        saveAllInBatches(newEdges, BATCH_SIZE, edgeRepository::saveAll);

        Set<String> newEdgeEvidenceKeys = new HashSet<>();
        List<EdgeEvidence> linksToSave = new ArrayList<>();
        for (PendingEdgeEvidence pending : pendingEdgeEvidence) {
            if (pending.edge() == null || pending.edge().getEdgeId() == null || pending.evidence() == null) {
                continue;
            }

            Long edgeId = pending.edge().getEdgeId();
            Long evidenceId = pending.evidence().getEvidenceId();
            String linkKey = toEdgeEvidenceLinkKey(edgeId, evidenceId);
            if (existingEdgeEvidenceKeys.contains(linkKey) || !newEdgeEvidenceKeys.add(linkKey)) {
                continue;
            }

            EdgeEvidenceId edgeEvidenceId = new EdgeEvidenceId(edgeId, evidenceId);
            linksToSave.add(new EdgeEvidence(edgeEvidenceId, pending.edge(), pending.evidence()));
        }

        saveAllInBatches(linksToSave, BATCH_SIZE, edgeEvidenceRepository::saveAll);
        int edgeEvidenceSaved = linksToSave.size();

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
     * 기존 edge-evidence 연결 키를 메모리 Set으로 로드한다.
     */
    private Set<String> loadExistingEdgeEvidenceKeys(List<Edge> existingEdges) {
        List<Long> edgeIds = new ArrayList<>();
        for (Edge edge : existingEdges) {
            if (edge.getEdgeId() != null) {
                edgeIds.add(edge.getEdgeId());
            }
        }

        if (edgeIds.isEmpty()) {
            return new HashSet<>();
        }

        List<EdgeEvidence> existingLinks = edgeEvidenceRepository.findAllByEdge_EdgeIdIn(edgeIds);
        Set<String> keys = new HashSet<>(Math.max(16, existingLinks.size() * 2));
        for (EdgeEvidence link : existingLinks) {
            if (link.getId() == null) {
                continue;
            }
            keys.add(toEdgeEvidenceLinkKey(link.getId().getEdgeId(), link.getId().getEvidenceId()));
        }
        return keys;
    }

    private String toEdgeEvidenceLinkKey(Long edgeId, Long evidenceId) {
        return edgeId + ":" + evidenceId;
    }

    private EdgeKey toEdgeKey(Edge edge) {
        String toSymbolId = edge.getToSymbol() == null ? null : edge.getToSymbol().getSymbolId();
        String toRawRefCanonical = toSymbolId == null ? canonicalJson(edge.getToRawRef()) : null;
        return new EdgeKey(
                edge.getFromSymbol().getSymbolId(),
                edge.getEdgeType(),
                toSymbolId,
                toRawRefCanonical
        );
    }

    private EdgeKey toEdgeKey(SymbolEntity from, Edge edge) {
        String toSymbolId = edge.getToSymbol() == null ? null : edge.getToSymbol().getSymbolId();
        String toRawRefCanonical = toSymbolId == null ? canonicalJson(edge.getToRawRef()) : null;
        return new EdgeKey(from.getSymbolId(), edge.getEdgeType(), toSymbolId, toRawRefCanonical);
    }

    /**
     * 리스트를 청크 단위로 saveAll 처리한다.
     */
    private <T> void saveAllInBatches(List<T> values, int batchSize, Consumer<List<T>> saver) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (int start = 0; start < values.size(); start += batchSize) {
            int end = Math.min(start + batchSize, values.size());
            saver.accept(values.subList(start, end));
        }
    }

    /**
     * 목적지 심볼을 우선 dstSymbol로 찾고, 없으면 dstRawRef를 기반으로 보조 해석한다.
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

    private String stripTypeDecorations(String value) {
        String current = value.trim();
        for (String prefix : List.of("class ", "interface ", "enum ", "record ")) {
            if (current.startsWith(prefix)) {
                return current.substring(prefix.length()).trim();
            }
        }
        return current;
    }

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

    private void addTypeLookupKey(Map<String, SymbolEntity> index, String rawKey, SymbolEntity symbol) {
        String normalized = normalizeTypeRefForLookup(rawKey);
        if (normalized == null) {
            return;
        }

        for (String candidate : buildTypeLookupCandidates(normalized)) {
            index.putIfAbsent(candidate, symbol);
        }
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

    private record EvidenceLookupIndexes(
            Map<String, Evidence> hashLookup,
            Map<EvidenceSignature, Evidence> fileSignatureLookup,
            Map<EvidenceSignature, Evidence> lineSignatureLookup
    ) {
    }

    private record EvidenceSignature(
            EvidenceType evidenceType,
            Long fileId,
            Integer startLine,
            Integer endLine,
            String snippet
    ) {
    }

    private record EdgeKey(
            String fromSymbolId,
            EdgeType edgeType,
            String toSymbolId,
            String toRawRefCanonical
    ) {
    }

    private record PendingEdgeEvidence(Edge edge, Evidence evidence) {
    }
}
