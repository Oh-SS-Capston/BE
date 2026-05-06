// 역할: graphstore/publicapi/ranking 데이터를 화면용 Public API Class Map JSON으로 조립한다.
package com.example.ossdoc.domain.classmap.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.classmap.artifact.output.*;
import com.example.ossdoc.domain.classmap.dto.request.ClassMapBuildRequest;
import com.example.ossdoc.domain.classmap.dto.response.ClassMapBuildResponse;
import com.example.ossdoc.domain.classmap.exception.ClassMapException;
import com.example.ossdoc.domain.classmap.exception.code.ClassMapErrorCode;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.service.PublicApiEntrySyncService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassMapBuildService {
    private static final Set<String> HIDDEN_PACKAGE_TOKENS = Set.of("internal", "impl", "support", "generated", "test");
    private static final List<EdgeType> INCLUDED_EDGE_TYPES = List.of(
            EdgeType.EXTENDS,
            EdgeType.IMPLEMENTS,
            EdgeType.PARAM,
            EdgeType.RETURNS
    );
    private static final List<EdgeType> HIDDEN_EDGE_TYPES = List.of(
            EdgeType.CALLS,
            EdgeType.HAS_FIELD,
            EdgeType.ACCESSES_FIELD,
            EdgeType.CONTAINS,
            EdgeType.OVERRIDES,
            EdgeType.ANNOTATED_WITH,
            EdgeType.THROWS
    );

    private final RepoRunRepository repoRunRepository;
    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final PublicApiEntrySyncService publicApiEntrySyncService;
    private final ClassMapArtifactPublisher classMapArtifactPublisher;

    /**
     * run 소유권을 검증한 뒤 클래스 맵을 생성하고 artifact로 저장한다.
     */
    @Transactional
    public ClassMapBuildResponse build(ClassMapBuildRequest request, Long userId) {
        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new ClassMapException(ClassMapErrorCode.CLASS_MAP_RUN_NOT_FOUND));

        if (run.getOwner() == null || !Objects.equals(run.getOwner().getId(), userId)) {
            throw new ClassMapException(ClassMapErrorCode.CLASS_MAP_FORBIDDEN);
        }

        try {
            List<SymbolEntity> typeSymbols = symbolRepository.findAllByRun_RunIdAndSymbolKind(run.getRunId(), SymbolKind.TYPE);
            List<SymbolEntity> methodSymbols = symbolRepository.findAllByRun_RunIdAndSymbolKind(run.getRunId(), SymbolKind.METHOD);
            List<Edge> allEdges = edgeRepository.findAllByRun_RunId(run.getRunId());
            Set<String> publicApiTypeIds = publicApiEntrySyncService.ensureTypeEntries(run, typeSymbols);

            Map<String, SymbolEntity> typeById = typeSymbols.stream()
                    .collect(Collectors.toMap(SymbolEntity::getSymbolId, s -> s, (a, b) -> a, LinkedHashMap::new));
            Map<String, SymbolEntity> methodById = methodSymbols.stream()
                    .collect(Collectors.toMap(SymbolEntity::getSymbolId, s -> s, (a, b) -> a, LinkedHashMap::new));

            FilterResult filterResult = filterCandidateTypes(typeSymbols, publicApiTypeIds);
            Set<String> candidateTypeIds = filterResult.candidateTypeIds();
            if (candidateTypeIds.isEmpty()) {
                throw new ClassMapException(ClassMapErrorCode.CLASS_MAP_NO_VISIBLE_TYPES);
            }

            AggregateResult aggregateResult = aggregateEdges(allEdges, candidateTypeIds, methodById);

            Set<String> configTypeIds = detectConfigTypes(candidateTypeIds, typeById, allEdges);
            Set<String> extensionPointTypeIds = detectExtensionPointTypes(candidateTypeIds, typeById, allEdges);

            Map<String, Double> scoreByTypeId = buildTypeScoreMap(
                    candidateTypeIds,
                    aggregateResult.degreeByTypeId(),
                    publicApiTypeIds,
                    configTypeIds,
                    extensionPointTypeIds
            );
            Set<String> startHereTypeIds = pickStartHereTypes(
                    candidateTypeIds,
                    scoreByTypeId,
                    request.safeStartHereTopN()
            );

            Set<String> inputModelTypeIds = aggregateResult.paramIncomingByType().entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            Set<String> outputModelTypeIds = aggregateResult.returnIncomingByType().entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            Comparator<String> nodePriority = nodePriorityComparator(scoreByTypeId, typeById);
            LinkedHashSet<String> selectedTypeIds = selectTypeIds(
                    candidateTypeIds,
                    publicApiTypeIds,
                    startHereTypeIds,
                    configTypeIds,
                    extensionPointTypeIds,
                    inputModelTypeIds,
                    outputModelTypeIds,
                    nodePriority,
                    request.safeMaxNodes()
            );

            List<EdgeAggregate> selectedAggregates = aggregateResult.aggregates().values().stream()
                    .filter(aggregate -> selectedTypeIds.contains(aggregate.sourceTypeId))
                    .filter(aggregate -> selectedTypeIds.contains(aggregate.targetTypeId))
                    .sorted(edgePriorityComparator())
                    .limit(request.safeMaxEdges())
                    .toList();

            Map<Long, List<Evidence>> evidenceByEdgeId = loadEvidenceByEdgeId(selectedAggregates);
            Set<String> exampleUsedTypeIds = new HashSet<>();

            List<ClassDiagramEdge> edgeItems = new ArrayList<>();
            for (EdgeAggregate aggregate : selectedAggregates) {
                EvidenceBundle evidenceBundle = buildEvidenceBundle(aggregate, evidenceByEdgeId);
                if (evidenceBundle.hasExampleEvidence) {
                    exampleUsedTypeIds.add(aggregate.sourceTypeId);
                    exampleUsedTypeIds.add(aggregate.targetTypeId);
                }
                edgeItems.add(ClassDiagramEdge.builder()
                        .sourceSymbolId(aggregate.sourceTypeId)
                        .targetSymbolId(aggregate.targetTypeId)
                        .edgeType(aggregate.edgeType.name())
                        .label(edgeLabel(aggregate.edgeType))
                        .evidenceCount(evidenceBundle.evidence.getCount())
                        .confidence(aggregate.maxConfidence())
                        .resolution(aggregate.resolution().name())
                        .badges(edgeBadges(aggregate.edgeType))
                        .evidence(evidenceBundle.evidence)
                        .build());
            }

            List<ClassDiagramNode> nodeItems = new ArrayList<>();
            for (String symbolId : selectedTypeIds) {
                SymbolEntity type = typeById.get(symbolId);
                if (type == null) {
                    continue;
                }
                BadgePack badgePack = buildNodeBadges(
                        symbolId,
                        publicApiTypeIds,
                        startHereTypeIds,
                        configTypeIds,
                        extensionPointTypeIds,
                        inputModelTypeIds,
                        outputModelTypeIds,
                        exampleUsedTypeIds
                );

                nodeItems.add(ClassDiagramNode.builder()
                        .symbolId(symbolId)
                        .label(resolveNodeLabel(type))
                        .qualifiedName(normalizeQualifiedName(type.getQualifiedName()))
                        .packageName(extractPackageName(type))
                        .moduleId(type.getModule() == null ? null : type.getModule().getModuleId())
                        .access(type.getAccess() == null ? null : type.getAccess().name())
                        .score(scoreByTypeId.get(symbolId))
                        .badges(badgePack.badges)
                        .reasons(badgePack.reasons)
                        .build());
            }

            ClassDiagramJson classDiagramJson = ClassDiagramJson.builder()
                    .schemaVersion("1.0")
                    .runId(run.getRunId())
                    .generatedAt(OffsetDateTime.now())
                    .summary(ClassDiagramSummary.builder()
                            .totalTypeCount(typeSymbols.size())
                            .candidateTypeCount(candidateTypeIds.size())
                            .selectedNodeCount(nodeItems.size())
                            .selectedEdgeCount(edgeItems.size())
                            .hiddenByAccessCount(filterResult.hiddenByAccessCount())
                            .hiddenByPackageCount(filterResult.hiddenByPackageCount())
                            .build())
                    .displayPolicy(ClassDiagramDisplayPolicy.builder()
                            .hiddenPackageTokens(HIDDEN_PACKAGE_TOKENS.stream().sorted().toList())
                            .includedEdgeTypes(INCLUDED_EDGE_TYPES.stream().map(Enum::name).toList())
                            .hiddenEdgeTypes(HIDDEN_EDGE_TYPES.stream().map(Enum::name).toList())
                            .build())
                    .nodes(nodeItems)
                    .edges(edgeItems)
                    .build();

            Artifact artifact = classMapArtifactPublisher.publishClassDiagram(run, classDiagramJson);
            return new ClassMapBuildResponse(run.getRunId(), nodeItems.size(), edgeItems.size(), artifact.getArtifactId());
        } catch (ClassMapException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassMapException(ClassMapErrorCode.CLASS_MAP_BUILD_FAILED);
        }
    }

    /**
     * 접근제어자/패키지 정책으로 화면 후보 타입을 필터링한다.
     */
    private FilterResult filterCandidateTypes(List<SymbolEntity> typeSymbols, Set<String> publicApiTypeIds) {
        int hiddenByAccess = 0;
        int hiddenByPackage = 0;
        LinkedHashSet<String> candidateTypeIds = new LinkedHashSet<>();

        for (SymbolEntity type : typeSymbols) {
            boolean forceInclude = publicApiTypeIds.contains(type.getSymbolId());
            boolean visibleApi = isPublicOrProtected(type.getAccess());
            if (!forceInclude && !visibleApi) {
                hiddenByAccess++;
                continue;
            }

            boolean hiddenPackage = isHiddenPackage(extractPackageName(type));
            if (!forceInclude && hiddenPackage) {
                hiddenByPackage++;
                continue;
            }

            candidateTypeIds.add(type.getSymbolId());
        }

        return new FilterResult(candidateTypeIds, hiddenByAccess, hiddenByPackage);
    }

    /**
     * 표시 대상 edge를 타입 레벨로 집계하고 degree/입출력 통계를 만든다.
     */
    private AggregateResult aggregateEdges(
            List<Edge> allEdges,
            Set<String> candidateTypeIds,
            Map<String, SymbolEntity> methodById
    ) {
        Map<String, EdgeAggregate> aggregateMap = new LinkedHashMap<>();
        Map<String, Integer> degreeByTypeId = new HashMap<>();
        Map<String, Integer> paramIncomingByType = new HashMap<>();
        Map<String, Integer> returnIncomingByType = new HashMap<>();

        for (Edge edge : allEdges) {
            if (edge.getFromSymbol() == null || edge.getToSymbol() == null) {
                continue;
            }

            if (edge.getEdgeType() == EdgeType.EXTENDS || edge.getEdgeType() == EdgeType.IMPLEMENTS) {
                if (edge.getFromSymbol().getSymbolKind() != SymbolKind.TYPE || edge.getToSymbol().getSymbolKind() != SymbolKind.TYPE) {
                    continue;
                }
                String src = edge.getFromSymbol().getSymbolId();
                String dst = edge.getToSymbol().getSymbolId();
                if (!candidateTypeIds.contains(src) || !candidateTypeIds.contains(dst)) {
                    continue;
                }
                String key = edgeKey(src, dst, edge.getEdgeType());
                aggregateMap.computeIfAbsent(key, ignored -> new EdgeAggregate(src, dst, edge.getEdgeType())).addEdge(edge);
            }
        }

        for (Edge edge : allEdges) {
            if (edge.getEdgeType() != EdgeType.PARAM && edge.getEdgeType() != EdgeType.RETURNS) {
                continue;
            }
            if (edge.getFromSymbol() == null || edge.getToSymbol() == null) {
                continue;
            }
            SymbolEntity method = methodById.get(edge.getFromSymbol().getSymbolId());
            if (method == null || !isPublicOrProtected(method.getAccess())) {
                continue;
            }
            SymbolEntity ownerType = method.getOwner();
            if (ownerType == null) {
                continue;
            }
            String src = ownerType.getSymbolId();
            String dst = edge.getToSymbol().getSymbolId();
            if (!candidateTypeIds.contains(src) || !candidateTypeIds.contains(dst)) {
                continue;
            }
            String key = edgeKey(src, dst, edge.getEdgeType());
            aggregateMap.computeIfAbsent(key, ignored -> new EdgeAggregate(src, dst, edge.getEdgeType())).addEdge(edge);
        }

        for (EdgeAggregate aggregate : aggregateMap.values()) {
            int count = aggregate.rawEdges.size();
            degreeByTypeId.merge(aggregate.sourceTypeId, count, Integer::sum);
            degreeByTypeId.merge(aggregate.targetTypeId, count, Integer::sum);
            if (aggregate.edgeType == EdgeType.PARAM) {
                paramIncomingByType.merge(aggregate.targetTypeId, count, Integer::sum);
            }
            if (aggregate.edgeType == EdgeType.RETURNS) {
                returnIncomingByType.merge(aggregate.targetTypeId, count, Integer::sum);
            }
        }

        return new AggregateResult(aggregateMap, degreeByTypeId, paramIncomingByType, returnIncomingByType);
    }

    /**
     * 이름 규칙과 어노테이션 흔적을 바탕으로 설정 클래스를 감지한다.
     */
    private Set<String> detectConfigTypes(
            Set<String> candidateTypeIds,
            Map<String, SymbolEntity> typeById,
            List<Edge> allEdges
    ) {
        Set<String> result = new HashSet<>();

        for (String typeId : candidateTypeIds) {
            SymbolEntity type = typeById.get(typeId);
            if (type == null) {
                continue;
            }
            String simpleName = trimToNull(type.getSimpleName());
            if (simpleName != null && simpleName.endsWith("Config")) {
                result.add(typeId);
            }
        }

        for (Edge edge : allEdges) {
            if (edge.getEdgeType() != EdgeType.ANNOTATED_WITH || edge.getFromSymbol() == null) {
                continue;
            }
            if (edge.getFromSymbol().getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }
            String srcTypeId = edge.getFromSymbol().getSymbolId();
            if (!candidateTypeIds.contains(srcTypeId)) {
                continue;
            }
            if (looksLikeConfigurationAnnotation(edge)) {
                result.add(srcTypeId);
            }
        }
        return result;
    }

    /**
     * 확장 포인트(추상 타입/구현 대상 인터페이스)를 감지한다.
     */
    private Set<String> detectExtensionPointTypes(
            Set<String> candidateTypeIds,
            Map<String, SymbolEntity> typeById,
            List<Edge> allEdges
    ) {
        Set<String> implementedTargets = new HashSet<>();
        for (Edge edge : allEdges) {
            if (edge.getEdgeType() != EdgeType.IMPLEMENTS || edge.getToSymbol() == null) {
                continue;
            }
            if (edge.getToSymbol().getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }
            implementedTargets.add(edge.getToSymbol().getSymbolId());
        }

        Set<String> result = new HashSet<>();
        for (String typeId : candidateTypeIds) {
            SymbolEntity type = typeById.get(typeId);
            if (type == null) {
                continue;
            }
            if (isAbstractType(type) || implementedTargets.contains(typeId)) {
                result.add(typeId);
            }
        }
        return result;
    }

    /**
     * 타입별 대표 점수를 계산한다. ranking 점수를 우선 사용하고 없으면 degree를 사용한다.
     */
    private Map<String, Double> buildTypeScoreMap(
            Set<String> candidateTypeIds,
            Map<String, Integer> degreeByTypeId,
            Set<String> publicApiTypeIds,
            Set<String> configTypeIds,
            Set<String> extensionPointTypeIds
    ) {
        Map<String, Double> scoreByTypeId = new HashMap<>();
        for (String typeId : candidateTypeIds) {
            int degree = degreeByTypeId.getOrDefault(typeId, 0);
            double score = degree;
            if (publicApiTypeIds.contains(typeId)) {
                score += 3.0;
            }
            if (configTypeIds.contains(typeId)) {
                score += 1.5;
            }
            if (extensionPointTypeIds.contains(typeId)) {
                score += 1.5;
            }
            scoreByTypeId.put(typeId, score);
        }
        return scoreByTypeId;
    }

    /**
     * Start Here 후보를 상위 N개 선택한다.
     */
    private Set<String> pickStartHereTypes(
            Set<String> candidateTypeIds,
            Map<String, Double> scoreByTypeId,
            int topN
    ) {
        return candidateTypeIds.stream()
                .sorted((left, right) -> Double.compare(
                        scoreByTypeId.getOrDefault(right, 0.0),
                        scoreByTypeId.getOrDefault(left, 0.0)
                ))
                .limit(topN)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 노드 선택 시 사용할 우선순위 비교기를 만든다.
     */
    private Comparator<String> nodePriorityComparator(
            Map<String, Double> scoreByTypeId,
            Map<String, SymbolEntity> typeById
    ) {
        return (left, right) -> {
            int byScore = Double.compare(
                    scoreByTypeId.getOrDefault(right, 0.0),
                    scoreByTypeId.getOrDefault(left, 0.0)
            );
            if (byScore != 0) {
                return byScore;
            }

            String leftName = normalizeQualifiedName(typeById.get(left) == null ? null : typeById.get(left).getQualifiedName());
            String rightName = normalizeQualifiedName(typeById.get(right) == null ? null : typeById.get(right).getQualifiedName());
            return safeString(leftName).compareTo(safeString(rightName));
        };
    }

    /**
     * maxNodes 한도 내에서 필수/모델/나머지 순으로 최종 노드를 선택한다.
     */
    private LinkedHashSet<String> selectTypeIds(
            Set<String> candidateTypeIds,
            Set<String> publicApiTypeIds,
            Set<String> startHereTypeIds,
            Set<String> configTypeIds,
            Set<String> extensionPointTypeIds,
            Set<String> inputModelTypeIds,
            Set<String> outputModelTypeIds,
            Comparator<String> nodePriority,
            int maxNodes
    ) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();

        List<String> mandatory = candidateTypeIds.stream()
                .filter(id -> publicApiTypeIds.contains(id)
                        || startHereTypeIds.contains(id)
                        || configTypeIds.contains(id)
                        || extensionPointTypeIds.contains(id))
                .sorted(nodePriority)
                .toList();
        for (String id : mandatory) {
            if (selected.size() >= maxNodes) {
                return selected;
            }
            selected.add(id);
        }

        List<String> models = candidateTypeIds.stream()
                .filter(id -> inputModelTypeIds.contains(id) || outputModelTypeIds.contains(id))
                .filter(id -> !selected.contains(id))
                .sorted(nodePriority)
                .toList();
        for (String id : models) {
            if (selected.size() >= maxNodes) {
                return selected;
            }
            selected.add(id);
        }

        List<String> rest = candidateTypeIds.stream()
                .filter(id -> !selected.contains(id))
                .sorted(nodePriority)
                .toList();
        for (String id : rest) {
            if (selected.size() >= maxNodes) {
                break;
            }
            selected.add(id);
        }
        return selected;
    }

    /**
     * edge 출력 순서를 결정하는 비교기를 만든다.
     */
    private Comparator<EdgeAggregate> edgePriorityComparator() {
        return (left, right) -> {
            int typeOrder = Integer.compare(edgeTypeOrder(left.edgeType), edgeTypeOrder(right.edgeType));
            if (typeOrder != 0) {
                return typeOrder;
            }
            int byEvidenceCount = Integer.compare(right.rawEdges.size(), left.rawEdges.size());
            if (byEvidenceCount != 0) {
                return byEvidenceCount;
            }
            int bySource = left.sourceTypeId.compareTo(right.sourceTypeId);
            if (bySource != 0) {
                return bySource;
            }
            return left.targetTypeId.compareTo(right.targetTypeId);
        };
    }

    /**
     * 선택된 edge들의 evidence를 edge id 기준으로 로드한다.
     */
    private Map<Long, List<Evidence>> loadEvidenceByEdgeId(List<EdgeAggregate> selectedAggregates) {
        List<Long> edgeIds = selectedAggregates.stream()
                .flatMap(aggregate -> aggregate.rawEdges.stream())
                .map(Edge::getEdgeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (edgeIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<Evidence>> evidenceByEdgeId = new HashMap<>();
        List<EdgeEvidence> rows = edgeEvidenceRepository.findAllByEdge_EdgeIdIn(edgeIds);
        for (EdgeEvidence row : rows) {
            if (row.getEdge() == null || row.getEvidence() == null || row.getEdge().getEdgeId() == null) {
                continue;
            }
            evidenceByEdgeId.computeIfAbsent(row.getEdge().getEdgeId(), ignored -> new ArrayList<>())
                    .add(row.getEvidence());
        }
        return evidenceByEdgeId;
    }

    /**
     * edge에 연결된 근거를 요약하고 example/readme 사용 여부를 계산한다.
     */
    private EvidenceBundle buildEvidenceBundle(EdgeAggregate aggregate, Map<Long, List<Evidence>> evidenceByEdgeId) {
        List<Evidence> all = new ArrayList<>();
        for (Edge edge : aggregate.rawEdges) {
            if (edge.getEdgeId() == null) {
                continue;
            }
            List<Evidence> evidences = evidenceByEdgeId.get(edge.getEdgeId());
            if (evidences == null || evidences.isEmpty()) {
                continue;
            }
            all.addAll(evidences);
        }

        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (Evidence evidence : all) {
            if (evidence.getEvidenceType() == null) {
                continue;
            }
            types.add(evidence.getEvidenceType().name());
        }

        boolean hasExampleEvidence = types.contains(EvidenceType.README.name()) || types.contains(EvidenceType.TEST.name());

        List<ClassDiagramEvidenceSample> samples = all.stream()
                .filter(Objects::nonNull)
                .limit(2)
                .map(evidence -> ClassDiagramEvidenceSample.builder()
                        .filePath(evidence.getFile() == null ? null : evidence.getFile().getPath())
                        .startLine(evidence.getStartLine())
                        .endLine(evidence.getEndLine())
                        .snippet(trimSnippet(evidence.getSnippet()))
                        .build())
                .toList();

        return new EvidenceBundle(
                ClassDiagramEvidence.builder()
                        .count(all.size())
                        .evidenceTypes(types.stream().toList())
                        .samples(samples)
                        .build(),
                hasExampleEvidence
        );
    }

    /**
     * 노드에 부여할 badge와 reason을 조립한다.
     */
    private BadgePack buildNodeBadges(
            String symbolId,
            Set<String> publicApiTypeIds,
            Set<String> startHereTypeIds,
            Set<String> configTypeIds,
            Set<String> extensionPointTypeIds,
            Set<String> inputModelTypeIds,
            Set<String> outputModelTypeIds,
            Set<String> exampleUsedTypeIds
    ) {
        List<String> badges = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        if (publicApiTypeIds.contains(symbolId)) {
            badges.add("public_api");
            reasons.add("public_api_entry");
        }
        if (startHereTypeIds.contains(symbolId)) {
            badges.add("start_here");
            reasons.add("high_priority_usage_entry");
        }
        if (configTypeIds.contains(symbolId)) {
            badges.add("config");
            reasons.add("configuration_type");
        }
        if (extensionPointTypeIds.contains(symbolId)) {
            badges.add("extension_point");
            reasons.add("extension_contract");
        }
        if (inputModelTypeIds.contains(symbolId)) {
            badges.add("input_model");
            reasons.add("public_method_param_target");
        }
        if (outputModelTypeIds.contains(symbolId)) {
            badges.add("output_model");
            reasons.add("public_method_return_target");
        }
        if (exampleUsedTypeIds.contains(symbolId)) {
            badges.add("example_used");
            reasons.add("readme_or_test_evidence");
        }
        return new BadgePack(List.copyOf(badges), List.copyOf(reasons));
    }

    /**
     * edge 타입에 따라 UI badge를 결정한다.
     */
    private List<String> edgeBadges(EdgeType edgeType) {
        if (edgeType == EdgeType.PARAM || edgeType == EdgeType.RETURNS) {
            return List.of("public_method");
        }
        return List.of("type_hierarchy");
    }

    /**
     * 노드 라벨을 simpleName 우선으로 계산한다.
     */
    private String resolveNodeLabel(SymbolEntity type) {
        String simpleName = trimToNull(type.getSimpleName());
        if (simpleName != null) {
            return simpleName;
        }

        String normalized = normalizeQualifiedName(type.getQualifiedName());
        if (normalized == null) {
            return type.getSymbolId();
        }

        int idx = normalized.lastIndexOf('.');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    /**
     * 타입의 패키지명을 qualified name에서 추출한다.
     */
    private String extractPackageName(SymbolEntity type) {
        String normalized = normalizeQualifiedName(type == null ? null : type.getQualifiedName());
        if (normalized == null) {
            return "";
        }
        int idx = normalized.lastIndexOf('.');
        return idx < 0 ? "" : normalized.substring(0, idx);
    }

    /**
     * 숨김 대상 패키지 토큰 포함 여부를 검사한다.
     */
    private boolean isHiddenPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }

        String[] tokens = packageName.toLowerCase(Locale.ROOT).split("\\.");
        for (String token : tokens) {
            if (HIDDEN_PACKAGE_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 설정 관련 어노테이션으로 보이는 문자열 패턴인지 판별한다.
     */
    private boolean looksLikeConfigurationAnnotation(Edge edge) {
        String candidate = null;
        if (edge.getToSymbol() != null) {
            candidate = normalizeQualifiedName(edge.getToSymbol().getQualifiedName());
        }
        if (candidate == null && edge.getToRawRef() != null && edge.getToRawRef().has("raw")) {
            candidate = normalizeQualifiedName(edge.getToRawRef().path("raw").asText(null));
        }
        if (candidate == null) {
            return false;
        }

        return candidate.endsWith(".Configuration")
                || candidate.endsWith("Configuration")
                || candidate.endsWith(".EnableAutoConfiguration")
                || candidate.endsWith("SpringBootApplication");
    }

    /**
     * 공개 범위가 public/protected인지 확인한다.
     */
    private boolean isPublicOrProtected(AccessLevel access) {
        return access == AccessLevel.PUBLIC || access == AccessLevel.PROTECTED;
    }

    /**
     * modifiers JSON에 ABSTRACT가 포함되어 있는지 확인한다.
     */
    private boolean isAbstractType(SymbolEntity symbol) {
        JsonNode modifiers = symbol.getModifiers();
        if (modifiers == null || !modifiers.isArray()) {
            return false;
        }
        for (JsonNode modifier : modifiers) {
            if (modifier != null && modifier.isTextual() && "ABSTRACT".equalsIgnoreCase(modifier.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * symbol prefix(type:, method: 등)를 제거해 표시용 qualified name으로 정규화한다.
     */
    private String normalizeQualifiedName(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.startsWith("type:")) {
            return value.substring("type:".length());
        }
        if (value.startsWith("method:")) {
            return value.substring("method:".length());
        }
        if (value.startsWith("field:")) {
            return value.substring("field:".length());
        }
        if (value.startsWith("ctor:")) {
            return value.substring("ctor:".length());
        }
        if (value.startsWith("package:")) {
            return value.substring("package:".length());
        }
        return value;
    }

    /**
     * edge 타입별 정렬 우선순위 숫자를 반환한다.
     */
    private int edgeTypeOrder(EdgeType edgeType) {
        return switch (edgeType) {
            case EXTENDS -> 1;
            case IMPLEMENTS -> 2;
            case PARAM -> 3;
            case RETURNS -> 4;
            default -> 99;
        };
    }

    /**
     * edge 타입을 프런트 표시 라벨로 변환한다.
     */
    private String edgeLabel(EdgeType edgeType) {
        return switch (edgeType) {
            case EXTENDS -> "extends";
            case IMPLEMENTS -> "implements";
            case PARAM -> "param";
            case RETURNS -> "returns";
            default -> edgeType.name().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 스니펫 길이를 제한해 화면 과밀을 방지한다.
     */
    private String trimSnippet(String snippet) {
        String value = trimToNull(snippet);
        if (value == null) {
            return null;
        }
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    /**
     * 공백 문자열을 null로 정규화한다.
     */
    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * null-safe 문자열 비교를 위해 빈 문자열로 치환한다.
     */
    private String safeString(String value) {
        return value == null ? "" : value;
    }

    /**
     * edge 집계용 고유 key를 생성한다.
     */
    private String edgeKey(String sourceId, String targetId, EdgeType edgeType) {
        return sourceId + "|" + edgeType.name() + "|" + targetId;
    }

    private record FilterResult(Set<String> candidateTypeIds, int hiddenByAccessCount, int hiddenByPackageCount) {
    }

    private record AggregateResult(
            Map<String, EdgeAggregate> aggregates,
            Map<String, Integer> degreeByTypeId,
            Map<String, Integer> paramIncomingByType,
            Map<String, Integer> returnIncomingByType
    ) {
    }

    private record BadgePack(List<String> badges, List<String> reasons) {
    }

    private record EvidenceBundle(ClassDiagramEvidence evidence, boolean hasExampleEvidence) {
    }

    private static class EdgeAggregate {
        private final String sourceTypeId;
        private final String targetTypeId;
        private final EdgeType edgeType;
        private final List<Edge> rawEdges = new ArrayList<>();

        /**
         * 동일한 source/target/type 조합을 위한 집계 버킷을 만든다.
         */
        private EdgeAggregate(String sourceTypeId, String targetTypeId, EdgeType edgeType) {
            this.sourceTypeId = sourceTypeId;
            this.targetTypeId = targetTypeId;
            this.edgeType = edgeType;
        }

        /**
         * 동일 버킷에 속한 원본 edge를 누적한다.
         */
        private void addEdge(Edge edge) {
            rawEdges.add(edge);
        }

        /**
         * 버킷 내 edge confidence의 최댓값을 반환한다.
         */
        private Double maxConfidence() {
            return rawEdges.stream()
                    .map(Edge::getConfidence)
                    .filter(Objects::nonNull)
                    .map(BigDecimal::doubleValue)
                    .max(Double::compareTo)
                    .orElse(null);
        }

        /**
         * 버킷 내 edge들의 resolution을 보수적으로 요약한다.
         */
        private ResolutionStatus resolution() {
            int level = 0;
            for (Edge edge : rawEdges) {
                if (edge.getResolution() == null) {
                    continue;
                }
                if (edge.getResolution() == ResolutionStatus.UNRESOLVED) {
                    level = Math.max(level, 3);
                } else if (edge.getResolution() == ResolutionStatus.PARTIAL) {
                    level = Math.max(level, 2);
                } else {
                    level = Math.max(level, 1);
                }
            }
            return switch (level) {
                case 3 -> ResolutionStatus.UNRESOLVED;
                case 2 -> ResolutionStatus.PARTIAL;
                default -> ResolutionStatus.RESOLVED;
            };
        }
    }
}
