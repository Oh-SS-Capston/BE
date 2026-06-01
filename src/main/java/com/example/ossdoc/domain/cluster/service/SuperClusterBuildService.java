package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.cluster.artifact.output.SubsystemsJson;
import com.example.ossdoc.domain.cluster.artifact.output.SuperSubsystemsJson;
import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.dto.request.SuperClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.SuperClusterBuildResponse;
import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.model.subsystem.SuperSubsystem;
import com.example.ossdoc.domain.cluster.support.supercluster.MergeContext;
import com.example.ossdoc.domain.cluster.support.supercluster.ModuleResolver;
import com.example.ossdoc.domain.cluster.support.supercluster.SuperClusterMergeStrategy;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.module.entity.ModuleEntity;
import com.example.ossdoc.domain.module.repository.ModuleRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SuperClusterBuildService {

    private final RepoRunRepository repoRunRepository;
    private final ArtifactRepository artifactRepository;
    private final SymbolRepository symbolRepository;
    private final ModuleRepository moduleRepository;
    private final ClusterArtifactPublisher clusterArtifactPublisher;
    private final ClusterSignalProperties clusterSignalProperties;
    private final ObjectMapper objectMapper;
    private final List<SuperClusterMergeStrategy> strategies;

    public SuperClusterBuildResponse build(SuperClusterBuildRequest request) {
        String runId = request.getRunId();

        ClusterSignalProperties.SuperCluster config = clusterSignalProperties.getSuperCluster();
        if (!config.isEnabled()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_SUPER_DISABLED);
        }

        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new ClusterException(ClusterErrorCode.CLUSTER_RUN_NOT_FOUND));

        Artifact subsystemsArtifact = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.SUBSYSTEMS_JSON)
                .orElseThrow(() -> new ClusterException(ClusterErrorCode.CLUSTER_LEVEL1_NOT_FOUND));

        List<Subsystem> level1Subsystems = deserializeLevel1(runId, subsystemsArtifact);

        if (level1Subsystems.isEmpty()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
        }

        // Option 3: BUILD_MANIFEST 기반 sourceRoot 매칭 (DB module이 null인 과거 run 호환 fallback)
        ModuleResolver moduleResolver = loadModuleResolver(runId);

        // C-3: DB module 테이블에서 모듈 정보 로드 (C-1/C-2 이후 run에서 유효)
        // displayNames: Option 3 기준으로 시작 후 DB 값으로 덮어씀 (artifactId ?? name 우선)
        Map<String, String> displayNames = new LinkedHashMap<>(moduleResolver.displayNames());
        Map<String, ModuleEntity> dbModuleByKey = new LinkedHashMap<>();
        for (ModuleEntity m : moduleRepository.findAllByRun_RunId(runId)) {
            String key = extractModuleKey(m.getModuleId(), runId);
            if (key != null) {
                dbModuleByKey.put(key, m);
                String displayName = (m.getArtifactId() != null && !m.getArtifactId().isBlank())
                        ? m.getArtifactId() : m.getName();
                displayNames.put(key, displayName);
            }
        }

        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        buildIndexes(runId, nodeIndex, moduleResolver, dbModuleByKey,
                config.getFallback().getPackageRootCommonPrefixDepth());

        SuperClusterMergeStrategy strategy = resolveStrategy(config.getStrategy());
        MergeContext ctx = new MergeContext(level1Subsystems, nodeIndex, displayNames, config);

        List<SuperSubsystem> superSubsystems;
        try {
            superSubsystems = strategy.merge(ctx);
        } catch (Exception e) {
            log.error("[SUPER_CLUSTER] 병합 실패. runId={}, strategy={}", runId, config.getStrategy(), e);
            throw new ClusterException(ClusterErrorCode.CLUSTER_SUPER_BUILD_FAILED);
        }

        try {
            SuperSubsystemsJson dto = buildArtifactDto(runId, strategy, config, level1Subsystems, superSubsystems, nodeIndex);
            Artifact artifact = clusterArtifactPublisher.publishSuperSubsystems(run, dto);

            log.info("[SUPER_CLUSTER] 완료. runId={}, level1={}, level2={}, artifactId={}",
                    runId, level1Subsystems.size(), superSubsystems.size(), artifact.getArtifactId());

            return new SuperClusterBuildResponse(
                    runId,
                    level1Subsystems.size(),
                    superSubsystems.size(),
                    artifact.getArtifactId()
            );
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_ARTIFACT_SAVE_FAILED);
        }
    }

    private List<Subsystem> deserializeLevel1(String runId, Artifact artifact) {
        try {
            SubsystemsJson subsystemsJson = objectMapper.treeToValue(artifact.getMeta(), SubsystemsJson.class);
            List<Subsystem> subs = subsystemsJson.getSubsystems();
            return subs != null ? subs : List.of();
        } catch (Exception e) {
            log.error("[SUPER_CLUSTER] SUBSYSTEMS_JSON 역직렬화 실패. runId={}", runId, e);
            throw new ClusterException(ClusterErrorCode.CLUSTER_SUPER_BUILD_FAILED);
        }
    }

    /**
     * 노드 인덱스를 2-pass로 구성한다.
     *
     * <p>BUILD_MANIFEST sourceRoot 매칭은 커버리지가 부분적(JUnit5 ~65%, resilience4j ~57%)이라,
     * 매칭된 노드는 모듈 키({@code resilience4j-core})로, 미매칭 노드는 packageRoot 키
     * ({@code io.github.resilience4j.core})로 갈려 <b>같은 모듈이 두 super-cluster로 분열</b>한다.
     *
     * <p>이를 막기 위해 매칭된 노드로부터 {@code packageRoot → moduleKey} 다리(bridge)를 학습해,
     * 미매칭 노드도 동일 packageRoot면 같은 moduleKey로 흡수한다 → 단일 키 namespace로 통합.
     *
     * <ol>
     *   <li>pass 1: sourceRoot 직접 매칭 + 매칭 성공 노드로 packageRoot→moduleKey 투표 집계</li>
     *   <li>bridge 확정: packageRoot별 다수결 moduleKey (동수 시 사전식 최솟값)</li>
     *   <li>pass 2: 직접 매칭 우선, 없으면 bridge로 흡수, 그래도 없으면 null → packageRoot fallback</li>
     * </ol>
     */
    private void buildIndexes(String runId,
                              Map<String, ProjectedNode> nodeIndex,
                              ModuleResolver moduleResolver,
                              Map<String, ModuleEntity> dbModuleByKey,
                              int fallbackDepth) {
        try {
            List<SymbolEntity> typeSymbols = symbolRepository
                    .findAllByRun_RunIdAndSymbolKindOrderBySymbolIdAsc(runId, SymbolKind.TYPE);

            // pass 1: 직접 매칭 결과를 모으고, 매칭된 노드로 packageRoot→moduleKey 다리를 학습한다.
            List<RawNode> raws = new ArrayList<>(typeSymbols.size());
            Map<String, Map<String, Integer>> bridgeVotes = new HashMap<>();
            int dbMatched = 0;
            int option3Matched = 0;

            for (SymbolEntity symbol : typeSymbols) {
                String packageName = extractPackageName(symbol.getQualifiedName());

                // C-3: DB module 우선, null이면 Option 3 sourceRoot 매칭 fallback (과거 run 호환)
                // symbol.getModule()은 @Id 접근이므로 Hibernate proxy 초기화 없이 moduleId 반환
                String matchedModuleId;
                if (symbol.getModule() != null) {
                    matchedModuleId = extractModuleKey(symbol.getModule().getModuleId(), runId);
                    if (matchedModuleId != null) dbMatched++;
                } else {
                    matchedModuleId = moduleResolver.resolveModuleKey(symbol.getSourceRoot());
                    if (matchedModuleId != null) option3Matched++;
                }

                String packageRoot = extractPackagePrefix(packageName, fallbackDepth);

                raws.add(new RawNode(
                        symbol.getSymbolId(),
                        symbol.getQualifiedName(),
                        symbol.getSimpleName(),
                        packageName,
                        packageRoot,
                        matchedModuleId));

                if (matchedModuleId != null && packageRoot != null) {
                    bridgeVotes.computeIfAbsent(packageRoot, k -> new HashMap<>())
                            .merge(matchedModuleId, 1, Integer::sum);
                }
            }

            Map<String, String> bridge = resolveBridge(bridgeVotes);

            // pass 2: 직접 매칭 → bridge 흡수 → null(packageRoot fallback) 순으로 최종 moduleId 확정.
            int bridged = 0;
            for (RawNode r : raws) {
                String moduleId = r.matchedModuleId();
                if (moduleId == null && r.packageRoot() != null) {
                    moduleId = bridge.get(r.packageRoot());
                    if (moduleId != null) bridged++;
                }

                nodeIndex.put(r.symbolId(), ProjectedNode.builder()
                        .symbolId(r.symbolId())
                        .qualifiedName(r.qualifiedName())
                        .simpleName(r.simpleName())
                        .packageName(r.packageName())
                        .moduleId(moduleId)
                        .build());
            }

            log.info("[SUPER_CLUSTER] 모듈 매칭 완료. runId={}, total={}, dbMatched={}, option3Matched={}, bridgeKeys={}, bridgedNodes={}",
                    runId, raws.size(), dbMatched, option3Matched, bridge.size(), bridged);
        } catch (Exception e) {
            log.error("[SUPER_CLUSTER] 노드 인덱스 구성 실패. runId={}", runId, e);
            throw new ClusterException(ClusterErrorCode.CLUSTER_SUPER_BUILD_FAILED);
        }
    }

    /** packageRoot별 다수결 moduleKey 확정. 동수 시 사전식 최솟값(결정적). */
    private Map<String, String> resolveBridge(Map<String, Map<String, Integer>> bridgeVotes) {
        Map<String, String> bridge = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : bridgeVotes.entrySet()) {
            e.getValue().entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder())))
                    .ifPresent(top -> bridge.put(e.getKey(), top.getKey()));
        }
        return bridge;
    }

    private static String extractPackagePrefix(String packageName, int depth) {
        if (packageName == null || packageName.isBlank()) return null;
        String[] parts = packageName.split("\\.");
        int len = Math.min(depth, parts.length);
        return len == 0 ? null : String.join(".", java.util.Arrays.copyOfRange(parts, 0, len));
    }

    /** pass 1에서 수집한 노드 원본 (최종 moduleId는 pass 2에서 확정). */
    private record RawNode(
            String symbolId,
            String qualifiedName,
            String simpleName,
            String packageName,
            String packageRoot,
            String matchedModuleId
    ) {}

    /**
     * BUILD_MANIFEST artifact를 읽어 sourceRoot ↔ moduleId 매핑을 메모리로 구성한다.
     * artifact가 없거나 역직렬화 실패 시 빈 resolver를 반환해 packageRoot fallback으로 자연 degrade한다.
     */
    private ModuleResolver loadModuleResolver(String runId) {
        try {
            return artifactRepository
                    .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.BUILD_MANIFEST)
                    .map(a -> {
                        BuildManifest manifest;
                        try {
                            manifest = objectMapper.treeToValue(a.getMeta(), BuildManifest.class);
                        } catch (Exception e) {
                            log.warn("[SUPER_CLUSTER] BUILD_MANIFEST 역직렬화 실패. packageRoot fallback. runId={}", runId, e);
                            return ModuleResolver.empty();
                        }
                        ModuleResolver resolver = ModuleResolver.from(manifest == null ? List.of() : manifest.getModules());
                        log.info("[SUPER_CLUSTER] BUILD_MANIFEST 모듈 매핑 로드. runId={}, moduleCount={}",
                                runId, resolver.moduleCount());
                        return resolver;
                    })
                    .orElseGet(() -> {
                        log.warn("[SUPER_CLUSTER] BUILD_MANIFEST 없음. packageRoot fallback만 사용. runId={}", runId);
                        return ModuleResolver.empty();
                    });
        } catch (Exception e) {
            log.warn("[SUPER_CLUSTER] BUILD_MANIFEST 로드 실패. packageRoot fallback. runId={}", runId, e);
            return ModuleResolver.empty();
        }
    }

    private SuperSubsystemsJson buildArtifactDto(
            String runId,
            SuperClusterMergeStrategy strategy,
            ClusterSignalProperties.SuperCluster config,
            List<Subsystem> level1Subsystems,
            List<SuperSubsystem> superSubsystems,
            Map<String, ProjectedNode> nodeIndex) {

        int level1Count = level1Subsystems.size();
        int level2Count = (int) superSubsystems.stream()
                .filter(s -> !"super_misc".equals(s.getCanonicalKey())).count();
        int superMiscCount = superSubsystems.size() - level2Count;
        double foldRatio = level1Count == 0 ? 0.0 : (double) level2Count / level1Count;

        double avgDominantRatio = superSubsystems.stream()
                .mapToDouble(s -> {
                    if (s.getModuleAffinity() == null || s.getModuleAffinity().isEmpty()) return 0.0;
                    return s.getModuleAffinity().values().iterator().next(); // 첫 번째 = dominant (내림차순 정렬)
                })
                .average().orElse(0.0);

        long nodesWithModuleId = nodeIndex.values().stream()
                .filter(n -> n.getModuleId() != null).count();
        double moduleIdCoverage = nodeIndex.isEmpty() ? 0.0
                : (double) nodesWithModuleId / nodeIndex.size();
        int fallbackNodeCount = (int) (nodeIndex.size() - nodesWithModuleId);

        SuperSubsystemsJson.Metrics metrics = SuperSubsystemsJson.Metrics.builder()
                .level1Count(level1Count)
                .level2Count(level2Count)
                .foldRatio(round3(foldRatio))
                .superMiscCount(superMiscCount)
                .avgDominantRatio(round3(avgDominantRatio))
                .moduleIdCoverage(round3(moduleIdCoverage))
                .fallbackNodeCount(fallbackNodeCount)
                .build();

        SuperSubsystemsJson.Strategy strategyMeta = SuperSubsystemsJson.Strategy.builder()
                .name(strategy.strategyName())
                .minSuperSize(config.getMinSuperSize())
                .fallback(Map.of("packageRootCommonPrefixDepth",
                        config.getFallback().getPackageRootCommonPrefixDepth()))
                .metrics(metrics)
                .build();

        return SuperSubsystemsJson.builder()
                .schemaVersion("1.0")
                .runId(runId)
                .generatedAt(OffsetDateTime.now())
                .strategy(strategyMeta)
                .superSubsystems(superSubsystems)
                .build();
    }

    private SuperClusterMergeStrategy resolveStrategy(String strategyName) {
        return strategies.stream()
                .filter(s -> s.strategyName().startsWith(strategyName))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("[SUPER_CLUSTER] 전략 '{}' 없음, 첫 번째 전략 사용", strategyName);
                    return strategies.get(0);
                });
    }

    /**
     * DB moduleId ("runId::":gradlePath"") → ModuleResolver와 동일한 moduleKey.
     * ":resilience4j-core" → "resilience4j-core" (ModuleResolver.normalizeModuleKey와 동일 로직)
     */
    private static String extractModuleKey(String moduleId, String runId) {
        if (moduleId == null) return null;
        String prefix = runId + "::";
        if (!moduleId.startsWith(prefix)) return null;
        String gradlePath = moduleId.substring(prefix.length());
        while (gradlePath.startsWith(":")) gradlePath = gradlePath.substring(1);
        return gradlePath.isBlank() ? null : gradlePath;
    }

    private static String extractPackageName(String qualifiedName) {
        if (qualifiedName == null) return "";
        // graph의 qualifiedName은 "type:org.foo.Bar" 형식이므로 GraphProjectionService와 동일하게
        // ':' 앞 kind prefix를 먼저 떼어내야 packageRoot 키에 "type:"가 섞이지 않는다.
        int colonIdx = qualifiedName.indexOf(':');
        String fqn = colonIdx >= 0 ? qualifiedName.substring(colonIdx + 1) : qualifiedName;
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(0, lastDot) : "";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
