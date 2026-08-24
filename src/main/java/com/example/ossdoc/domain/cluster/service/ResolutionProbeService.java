package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Leiden에 사용할 적절한 resolution 값을 탐색하는 서비스.
 *
 * 여러 resolution 후보에 대해 Leiden을 실행하고,
 * CPM Quality를 중심으로 최적 후보를 선택한다.
 *
 * 추가로 작은 cluster 비율과 giant cluster 비율에 penalty를 적용하여
 * 지나치게 분할되거나 하나의 cluster에 집중되는 결과를 억제한다.
 *
 * Modularity는 최적 resolution 선택 기준이 아니라
 * 결과 비교 및 분석을 위한 보조 지표로 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionProbeService {

    // 최초 resolution 범위에서 최적값을 찾지 못했을 때 확장 탐색하는 최대 횟수.
    private static final int MAX_EXTENSION = 1;

    // 작은 cluster가 많이 발생하는 결과에 적용하는 penalty 계수.
    private static final double MISC_PENALTY = 0.35;

    // 하나의 cluster가 전체 graph 대부분을 차지하는 경우 적용하는 penalty 계수.
    private static final double GIANT_CLUSTER_PENALTY = 0.20;

    private final LeidenCommunityService leidenCommunityService;

    /**
     * resolution 탐색 결과.
     *
     * resolution      : 선택된 resolution
     * communityResult : 해당 resolution의 Leiden 결과
     * modularity      : 결과 분석용 Modularity
     * cpmQuality      : Leiden의 CPM Quality
     * clusterCount    : minClusterSize 이상인 유효 cluster 수
     */
    public record ProbeResult(
            double resolution,
            CommunityResult communityResult,
            double modularity,
            double cpmQuality,
            int clusterCount
    ) {}

    /**
     * 현재 graph에 적합한 Leiden resolution을 탐색한다.
     */
    public ProbeResult findBest(
            ProjectedGraph graph,
            int minClusterSize,
            int iterations
    ) {
        int n = graph.getNodes().size();

        if (n == 0) {
            return null;
        }

        // graph 전체 edge weight 합.
        double totalWeight = graph.getEdges()
                .stream()
                .mapToDouble(ProjectedEdge::getWeight)
                .sum();

        /*
         * weighted graph density.
         *
         * graph의 연결 밀도를 resolution 후보 범위 계산에 활용한다.
         */
        double weightedDensity = n <= 1
                ? 0.0
                : (2.0 * totalWeight) / ((double) n * (n - 1));

        /*
         * 일부 매우 큰 weight가 평균을 왜곡할 수 있으므로
         * 평균 대신 중앙값을 사용한다.
         */
        double medianEdgeWeight = medianEdgeWeight(graph);

        /*
         * graph density와 실제 edge weight scale을 함께 이용해
         * resolution 탐색 최소값을 계산한다.
         */
        double gammaMin = Math.max(
                0.001,
                Math.min(
                        weightedDensity * 0.20,
                        medianEdgeWeight * 0.10
                )
        );

        // resolution 탐색 최대값.
        double gammaMax = Math.max(
                gammaMin * 4.0,
                Math.min(
                        2.0,
                        medianEdgeWeight * 0.90
                )
        );

        /*
         * 매우 작은 프로젝트는 실제 subsystem이 하나일 수 있으므로
         * 최소 cluster 수를 1까지 허용한다.
         */
        int minClusters = n < 9 ? 1 : 3;

        /*
         * repository 규모에 따라 최대 허용 cluster 수도 증가시킨다.
         * 지나치게 많은 cluster가 생성되지 않도록 최대 40으로 제한한다.
         */
        int maxClusters = Math.max(
                minClusters,
                Math.min(
                        40,
                        (int) Math.ceil(Math.sqrt(n) * 1.5)
                )
        );

        /*
         * probe 단계에서는 너무 작은 cluster를 정상 subsystem으로 보지 않는다.
         */
        int probeMinClusterSize = Math.max(minClusterSize, 3);

        log.info(
                "[CLUSTER-PROBE] start. n={}, totalWeight={}, medianEdgeWeight={}, gamma=[{},{}], targetClusters=[{},{}]",
                n,
                fmt(totalWeight),
                fmt(medianEdgeWeight),
                fmt(gammaMin),
                fmt(gammaMax),
                minClusters,
                maxClusters
        );

        return probe(
                graph,
                gammaMin,
                gammaMax,
                decideCandidateCount(n),
                probeMinClusterSize,
                minClusters,
                maxClusters,
                iterations,
                0
        );
    }

    /**
     * 여러 resolution 후보를 실행하고 가장 좋은 결과를 선택한다.
     */
    private ProbeResult probe(
            ProjectedGraph graph,
            double gammaMin,
            double gammaMax,
            int candidateCount,
            int minClusterSize,
            int minClusters,
            int maxClusters,
            int iterations,
            int extensionCount
    ) {
        // resolution 값은 선형이 아니라 로그 스케일로 탐색한다.
        List<Double> candidates =
                logLinspace(gammaMin, gammaMax, candidateCount);

        ProbeResult best = null;
        ProbeResult fallback = null;

        double bestComposite = Double.NEGATIVE_INFINITY;
        int bestIdx = -1;

        for (int i = 0; i < candidates.size(); i++) {
            double resolution = candidates.get(i);

            /*
             * 각 resolution마다 Leiden을 수행한다.
             * 결과와 CPM Quality를 동시에 받아온다.
             */
            LeidenCommunityService.DetectionResult detected =
                    leidenCommunityService.detectWithQuality(
                            graph,
                            resolution,
                            iterations
                    );

            CommunityResult communityResult = detected.communityResult();

            // minClusterSize 이상인 cluster만 유효 cluster로 계산한다.
            int validCount = countValidClusters(
                    communityResult.getClusters(),
                    minClusterSize
            );

            /*
             * Modularity는 보조 분석 지표로 유지한다.
             * 최종 resolution 선택의 중심 기준은 CPM Quality다.
             */
            double modularity =
                    computeModularity(graph, communityResult);

            /*
             * 최종 후보 점수.
             *
             * CPM Quality
             * - small cluster penalty
             * - giant cluster penalty
             */
            double composite = computeCompositeScore(
                    graph,
                    communityResult,
                    detected.cpmQuality(),
                    minClusterSize
            );

            log.debug(
                    "[CLUSTER-PROBE] resolution={}, validClusters={}, CPM={}, modularity={}, composite={}",
                    fmt(resolution),
                    validCount,
                    fmt(detected.cpmQuality()),
                    fmt(modularity),
                    fmt(composite)
            );

            ProbeResult current = new ProbeResult(
                    resolution,
                    communityResult,
                    modularity,
                    detected.cpmQuality(),
                    validCount
            );

            /*
             * 정상 clusterCount 범위의 후보가 하나도 없을 경우를 대비해
             * 목표 범위에 가장 가까운 결과를 fallback으로 기억한다.
             */
            if (fallback == null || closerToRange(
                    validCount,
                    fallback.clusterCount(),
                    minClusters,
                    maxClusters
            )) {
                fallback = current;
            }

            // 너무 적거나 너무 많은 cluster가 생성되면 정상 최적 후보에서는 제외한다.
            if (validCount < minClusters || validCount > maxClusters) {
                continue;
            }

            // 정상 후보 중 composite score가 가장 높은 결과를 저장한다.
            if (best == null || composite > bestComposite) {
                best = current;
                bestComposite = composite;
                bestIdx = i;
            }
        }

        /*
         * 최적값이 현재 탐색 범위 끝에 있거나 정상 후보가 없다면
         * resolution 범위를 한 번 더 확장해서 탐색한다.
         */
        if (extensionCount < MAX_EXTENSION) {

            // 정상 범위의 후보가 하나도 없는 경우 양쪽 범위를 확장한다.
            if (best == null) {
                return probe(
                        graph,
                        Math.max(0.0001, gammaMin * 0.35),
                        gammaMax * 2.0,
                        candidateCount,
                        minClusterSize,
                        minClusters,
                        maxClusters,
                        iterations,
                        extensionCount + 1
                );
            }

            // 가장 작은 resolution이 최적이면 더 낮은 구간을 탐색한다.
            if (bestIdx == 0) {
                ProbeResult extended = probe(
                        graph,
                        Math.max(0.0001, gammaMin * 0.25),
                        gammaMin,
                        candidateCount,
                        minClusterSize,
                        minClusters,
                        maxClusters,
                        iterations,
                        extensionCount + 1
                );

                return better(graph, best, extended, minClusterSize);
            }

            // 가장 큰 resolution이 최적이면 더 높은 구간을 탐색한다.
            if (bestIdx == candidates.size() - 1) {
                ProbeResult extended = probe(
                        graph,
                        gammaMax,
                        gammaMax * 2.5,
                        candidateCount,
                        minClusterSize,
                        minClusters,
                        maxClusters,
                        iterations,
                        extensionCount + 1
                );

                return better(graph, best, extended, minClusterSize);
            }
        }

        // 정상 후보가 존재하면 best를 사용하고, 없으면 fallback을 사용한다.
        ProbeResult result = best != null ? best : fallback;

        if (result != null) {
            log.info(
                    "[CLUSTER-PROBE] done. resolution={}, clusters={}, CPM={}, modularity={}",
                    fmt(result.resolution()),
                    result.clusterCount(),
                    fmt(result.cpmQuality()),
                    fmt(result.modularity())
            );
        }

        return result;
    }

    /**
     * 두 후보 중 composite score가 높은 결과를 반환한다.
     */
    private ProbeResult better(
            ProjectedGraph graph,
            ProbeResult first,
            ProbeResult second,
            int minClusterSize
    ) {
        if (first == null) {
            return second;
        }

        if (second == null) {
            return first;
        }

        double firstScore = computeCompositeScore(
                graph,
                first.communityResult(),
                first.cpmQuality(),
                minClusterSize
        );

        double secondScore = computeCompositeScore(
                graph,
                second.communityResult(),
                second.cpmQuality(),
                minClusterSize
        );

        return secondScore > firstScore ? second : first;
    }

    /**
     * resolution 후보의 최종 평가 점수를 계산한다.
     */
    private double computeCompositeScore(
            ProjectedGraph graph,
            CommunityResult result,
            double cpmQuality,
            int minClusterSize
    ) {
        int n = graph.getNodes().size();

        if (n == 0) {
            return cpmQuality;
        }

        // minClusterSize보다 작은 cluster의 node 비율.
        double miscRatio = computeMiscRatio(
                result.getClusters(),
                minClusterSize,
                n
        );

        // 가장 큰 cluster가 전체 node에서 차지하는 비율.
        double maxClusterShare = computeMaxClusterShare(
                result.getClusters(),
                n
        );

        return cpmQuality
                - MISC_PENALTY * miscRatio
                - GIANT_CLUSTER_PENALTY * maxClusterShare;
    }

    /**
     * projected edge weight의 중앙값을 계산한다.
     */
    private double medianEdgeWeight(ProjectedGraph graph) {
        if (graph.getEdges().isEmpty()) {
            return 0.1;
        }

        double[] values = graph.getEdges()
                .stream()
                .mapToDouble(ProjectedEdge::getWeight)
                .sorted()
                .toArray();

        int mid = values.length / 2;

        if (values.length % 2 == 0) {
            return (values[mid - 1] + values[mid]) / 2.0;
        }

        return values[mid];
    }

    /**
     * candidate cluster 수가 현재 fallback보다 목표 범위에 더 가까운지 확인한다.
     */
    private boolean closerToRange(
            int candidate,
            int current,
            int min,
            int max
    ) {
        return distanceToRange(candidate, min, max)
                < distanceToRange(current, min, max);
    }

    /**
     * clusterCount와 목표 범위 사이의 거리를 계산한다.
     * 목표 범위 안이면 0이다.
     */
    private int distanceToRange(
            int value,
            int min,
            int max
    ) {
        if (value < min) {
            return min - value;
        }

        if (value > max) {
            return value - max;
        }

        return 0;
    }

    /**
     * minClusterSize 미만 cluster에 속한 node 비율을 계산한다.
     */
    private double computeMiscRatio(
            int[] clusters,
            int minClusterSize,
            int totalNodes
    ) {
        Map<Integer, Integer> counts = counts(clusters);

        int miscCount = counts.values()
                .stream()
                .filter(size -> size < minClusterSize)
                .mapToInt(Integer::intValue)
                .sum();

        if (totalNodes == 0) {
            return 0.0;
        }

        return (double) miscCount / totalNodes;
    }

    /**
     * 가장 큰 cluster가 전체 node에서 차지하는 비율을 계산한다.
     */
    private double computeMaxClusterShare(
            int[] clusters,
            int totalNodes
    ) {
        int max = counts(clusters)
                .values()
                .stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);

        if (totalNodes == 0) {
            return 0.0;
        }

        return (double) max / totalNodes;
    }

    /**
     * cluster ID별 node 개수를 계산한다.
     */
    private Map<Integer, Integer> counts(int[] clusters) {
        Map<Integer, Integer> counts = new HashMap<>();

        for (int cluster : clusters) {
            counts.merge(cluster, 1, Integer::sum);
        }

        return counts;
    }

    /**
     * min과 max 사이의 resolution 후보를 로그 스케일로 생성한다.
     */
    private List<Double> logLinspace(
            double min,
            double max,
            int count
    ) {
        List<Double> result = new ArrayList<>(count);

        double logMin = Math.log(min);
        double logMax = Math.log(max);

        for (int i = 0; i < count; i++) {
            double t = count == 1
                    ? 0.5
                    : (double) i / (count - 1);

            result.add(
                    Math.exp(logMin + t * (logMax - logMin))
            );
        }

        return result;
    }

    /**
     * minClusterSize 이상의 cluster 개수를 계산한다.
     */
    private int countValidClusters(
            int[] clusters,
            int minClusterSize
    ) {
        return (int) counts(clusters)
                .values()
                .stream()
                .filter(size -> size >= minClusterSize)
                .count();
    }

    /**
     * Weighted Modularity를 계산한다.
     *
     * 이 값은 resolution 선택 목적함수가 아니라
     * 기존 결과와의 비교 및 분석을 위한 diagnostic metric이다.
     */
    private double computeModularity(
            ProjectedGraph graph,
            CommunityResult result
    ) {
        int[] clusters = result.getClusters();
        int n = graph.getNodes().size();

        double[] degree = new double[n];
        double totalWeight = 0.0;

        // 각 node의 weighted degree와 전체 edge weight를 계산한다.
        for (ProjectedEdge edge : graph.getEdges()) {
            double weight = edge.getWeight();

            degree[edge.getFromIndex()] += weight;
            degree[edge.getToIndex()] += weight;

            totalWeight += weight;
        }

        double twoM = 2.0 * totalWeight;

        if (twoM == 0.0) {
            return 0.0;
        }

        // 각 cluster 내부 edge weight 합.
        Map<Integer, Double> internalWeight = new HashMap<>();

        for (ProjectedEdge edge : graph.getEdges()) {
            int fromCluster = clusters[edge.getFromIndex()];
            int toCluster = clusters[edge.getToIndex()];

            if (fromCluster == toCluster) {
                internalWeight.merge(
                        fromCluster,
                        2.0 * edge.getWeight(),
                        Double::sum
                );
            }
        }

        // 각 cluster에 속한 node의 weighted degree 합.
        Map<Integer, Double> degreeSum = new HashMap<>();

        for (int i = 0; i < n; i++) {
            degreeSum.merge(
                    clusters[i],
                    degree[i],
                    Double::sum
            );
        }

        double modularity = 0.0;

        for (int cluster : degreeSum.keySet()) {
            double internal = internalWeight.getOrDefault(cluster, 0.0);
            double total = degreeSum.get(cluster);

            modularity += (internal / twoM)
                    - Math.pow(total / twoM, 2);
        }

        return modularity;
    }

    /**
     * repository 크기에 따라 탐색할 resolution 후보 수를 결정한다.
     */
    private int decideCandidateCount(int n) {
        if (n <= 200) {
            return 9;
        }

        if (n <= 750) {
            return 7;
        }

        return 6;
    }

    /**
     * 로그 출력을 위해 double 값을 소수점 다섯 자리로 변환한다.
     */
    private String fmt(double value) {
        return String.format("%.5f", value);
    }
}