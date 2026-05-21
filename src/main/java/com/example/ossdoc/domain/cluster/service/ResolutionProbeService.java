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
 * 그래프 통계에서 resolution 탐색 범위를 유도하고, 로그 스케일 다중 프로브로
 * Modularity Q 기준 최적 resolution을 찾는다. (Method B)
 *
 * 흐름:
 * 1. N, M에서 γ_min(density×0.3), γ_max(avgDegree/N) 계산
 * 2. [γ_min, γ_max]를 로그 스케일 후보 5~7개로 분할
 * 3. 각 후보로 Leiden 실행 → 유효 클러스터 수 [3, min(10,√N)] 필터
 * 4. 유효 후보 중 Modularity Q 최고값 선택
 * 5. 최적이 경계에 걸리면 해당 방향으로 1회 확장 재탐색
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionProbeService {

    private final LeidenCommunityService leidenCommunityService;

    private static final int MAX_EXTENSION = 1;

    public record ProbeResult(
            double resolution,
            CommunityResult communityResult,
            double modularity,
            int clusterCount
    ) {}

    public ProbeResult findBest(ProjectedGraph graph, int minClusterSize, int iterations) {
        int n = graph.getNodes().size();

        double totalWeight = graph.getEdges().stream()
                .mapToDouble(ProjectedEdge::getWeight)
                .sum();
        double avgDegree = (totalWeight * 2.0) / n;
        double density   = (n <= 1) ? 0.0 : (totalWeight * 2.0) / ((double) n * (n - 1));

        double gammaMin = Math.max(density * 0.3, 0.005);
        double gammaMax = Math.min(avgDegree / n, 0.5);
        if (gammaMax <= gammaMin) gammaMax = gammaMin * 3.0;

        int minClusters = 3;
        int maxClusters = Math.max(minClusters, Math.min(10, (int) Math.sqrt(n)));

        log.info("[CLUSTER-PROBE] start. n={}, totalWeight={}, gammaMin={}, gammaMax={}, targetClusters=[{},{}]",
                n, String.format("%.3f", totalWeight),
                String.format("%.4f", gammaMin), String.format("%.4f", gammaMax),
                minClusters, maxClusters);

        // Probe 필터는 구조적 의미를 갖는 최소 크기(3)를 floor로 사용한다.
        // misc 분류 기준(minClusterSize)과 분리해 잡음 resolution이 유효로 판별되는 것을 막는다.
        int probeMinClusterSize = Math.max(minClusterSize, 3);

        return probe(graph, gammaMin, gammaMax, decideCandidateCount(n),
                probeMinClusterSize, minClusters, maxClusters, iterations, 0);
    }

    private ProbeResult probe(ProjectedGraph graph,
                               double gammaMin, double gammaMax, int candidateCount,
                               int minClusterSize, int minClusters, int maxClusters,
                               int iterations, int extensionCount) {

        List<Double> candidates = logLinspace(gammaMin, gammaMax, candidateCount);

        ProbeResult best     = null;
        ProbeResult fallback = null; // 유효 범위 밖에서 범위에 가장 근접한 후보
        int bestIdx = -1;

        for (int i = 0; i < candidates.size(); i++) {
            double resolution = candidates.get(i);
            CommunityResult cr = leidenCommunityService.detect(graph, resolution, iterations);
            int validCount = countValidClusters(cr.getClusters(), minClusterSize);
            double q = computeModularity(graph, cr);

            log.debug("[CLUSTER-PROBE] resolution={}, validClusters={}, Q={}",
                    String.format("%.4f", resolution), validCount, String.format("%.4f", q));

            if (fallback == null || closerToRange(validCount, fallback.clusterCount(), minClusters, maxClusters)) {
                fallback = new ProbeResult(resolution, cr, q, validCount);
            }

            if (validCount < minClusters || validCount > maxClusters) continue;

            if (best == null || q > best.modularity()) {
                best = new ProbeResult(resolution, cr, q, validCount);
                bestIdx = i;
            }
        }

        // 경계 확장 (최대 1회)
        if (extensionCount < MAX_EXTENSION) {
            if (best == null) {
                log.info("[CLUSTER-PROBE] no valid candidate. expanding both bounds: [{}, {}]",
                        String.format("%.4f", gammaMin * 0.5), String.format("%.4f", gammaMax * 2.0));
                return probe(graph, gammaMin * 0.5, gammaMax * 2.0, candidateCount,
                        minClusterSize, minClusters, maxClusters, iterations, extensionCount + 1);
            }
            if (bestIdx == 0) {
                log.info("[CLUSTER-PROBE] best at lower bound. extending lower: [{}, {}]",
                        String.format("%.4f", gammaMin * 0.3), String.format("%.4f", gammaMin));
                ProbeResult extended = probe(graph, gammaMin * 0.3, gammaMin, candidateCount,
                        minClusterSize, minClusters, maxClusters, iterations, extensionCount + 1);
                return extended.modularity() > best.modularity() ? extended : best;
            }
            if (bestIdx == candidates.size() - 1) {
                log.info("[CLUSTER-PROBE] best at upper bound. extending upper: [{}, {}]",
                        String.format("%.4f", gammaMax), String.format("%.4f", gammaMax * 2.5));
                ProbeResult extended = probe(graph, gammaMax, gammaMax * 2.5, candidateCount,
                        minClusterSize, minClusters, maxClusters, iterations, extensionCount + 1);
                return extended.modularity() > best.modularity() ? extended : best;
            }
        }

        if (best == null) {
            log.warn("[CLUSTER-PROBE] no valid candidate after extension. falling back to closest. resolution={}, clusters={}",
                    fallback != null ? String.format("%.4f", fallback.resolution()) : "N/A",
                    fallback != null ? fallback.clusterCount() : 0);
            return fallback;
        }

        log.info("[CLUSTER-PROBE] done. resolution={}, clusters={}, Q={}",
                String.format("%.4f", best.resolution()), best.clusterCount(),
                String.format("%.4f", best.modularity()));
        return best;
    }

    /**
     * [min, max] 구간을 로그 스케일로 count개 분할한다.
     * 낮은 구간에 후보가 촘촘하게 배치되어 resolution 효과의 비선형성을 보정한다.
     */
    private List<Double> logLinspace(double min, double max, int count) {
        List<Double> result = new ArrayList<>(count);
        double logMin = Math.log(min);
        double logMax = Math.log(max);
        for (int i = 0; i < count; i++) {
            double t = (count == 1) ? 0.5 : (double) i / (count - 1);
            double val = Math.exp(logMin + t * (logMax - logMin));
            result.add(Math.round(val * 10000.0) / 10000.0);
        }
        return result;
    }

    /**
     * minClusterSize 이상인 클러스터 수를 반환한다.
     */
    private int countValidClusters(int[] clusters, int minClusterSize) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int c : clusters) {
            counts.merge(c, 1, Integer::sum);
        }
        return (int) counts.values().stream().filter(s -> s >= minClusterSize).count();
    }

    /**
     * 표준 가중 Modularity Q를 계산한다.
     * Q = Σ_c [ in_c / 2W - (tot_c / 2W)² ]
     * in_c  : 커뮤니티 c 내부 엣지 가중치 합 (양방향)
     * tot_c : 커뮤니티 c 소속 노드들의 차수 합
     * 2W    : 전체 엣지 가중치 합의 2배
     */
    private double computeModularity(ProjectedGraph graph, CommunityResult result) {
        int[] clusters = result.getClusters();
        int n = graph.getNodes().size();

        double[] degree = new double[n];
        double totalWeight = 0.0;
        for (ProjectedEdge edge : graph.getEdges()) {
            double w = edge.getWeight();
            degree[edge.getFromIndex()] += w;
            degree[edge.getToIndex()]   += w;
            totalWeight += w;
        }

        double twoM = 2.0 * totalWeight;
        if (twoM == 0.0) return 0.0;

        Map<Integer, Double> internalWeight = new HashMap<>();
        for (ProjectedEdge edge : graph.getEdges()) {
            int ci = clusters[edge.getFromIndex()];
            int cj = clusters[edge.getToIndex()];
            if (ci == cj) {
                internalWeight.merge(ci, 2.0 * edge.getWeight(), Double::sum);
            }
        }

        Map<Integer, Double> degreeSum = new HashMap<>();
        for (int i = 0; i < n; i++) {
            degreeSum.merge(clusters[i], degree[i], Double::sum);
        }

        double q = 0.0;
        for (int c : degreeSum.keySet()) {
            double in  = internalWeight.getOrDefault(c, 0.0);
            double tot = degreeSum.get(c);
            q += (in / twoM) - Math.pow(tot / twoM, 2);
        }
        return q;
    }

    private int decideCandidateCount(int n) {
        if (n <= 200) return 7;
        if (n <= 750) return 6;
        return 5;
    }

    /**
     * count가 현재 best보다 [minC, maxC] 범위에 더 가까운지 비교한다.
     */
    private boolean closerToRange(int count, int bestCount, int minC, int maxC) {
        int distNew  = count    < minC ? minC - count    : (count    > maxC ? count    - maxC : 0);
        int distBest = bestCount < minC ? minC - bestCount : (bestCount > maxC ? bestCount - maxC : 0);
        return distNew < distBest;
    }
}
