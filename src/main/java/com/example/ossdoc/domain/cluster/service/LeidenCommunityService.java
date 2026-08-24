package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import lombok.extern.slf4j.Slf4j;
import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;
import org.springframework.stereotype.Service;

import java.util.Random;

/**
 * ProjectedGraph에 Leiden Community Detection을 수행하는 서비스.
 *
 * 기존 구현에서는 CommunityResult만 반환했지만,
 * 개선 버전에서는 Leiden이 실제 최적화하는 CPM 품질값도 함께 반환한다.
 *
 * 또한 동일 runId에 동일한 random seed를 사용하여
 * 같은 입력을 반복 분석했을 때 결과가 달라지는 현상을 줄인다.
 */
@Slf4j
@Service
public class LeidenCommunityService {

    // runId가 없을 때 사용하는 기본 seed.
    private static final long DEFAULT_LEIDEN_SEED = 31_415_926L;

    /**
     * Leiden 실행 결과.
     *
     * communityResult : 각 노드의 community 할당 결과
     * cpmQuality      : Leiden이 사용하는 CPM 품질함수 값
     */
    public record DetectionResult(
            CommunityResult communityResult,
            double cpmQuality
    ) {}

    /**
     * 기존 호출부와의 호환성을 위한 메서드.
     * CommunityResult만 필요한 경우 사용한다.
     */
    public CommunityResult detect(ProjectedGraph graph, double resolution, int iterations) {
        return detectWithQuality(graph, resolution, iterations).communityResult();
    }

    /**
     * Leiden을 실행하고 CommunityResult와 CPM Quality를 함께 반환한다.
     */
    public DetectionResult detectWithQuality(
            ProjectedGraph graph,
            double resolution,
            int iterations
    ) {
        try {
            int nNodes = graph.getNodes().size();

            // 노드가 없는 graph에는 Leiden을 수행할 수 없다.
            if (nNodes == 0) {
                throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
            }

            // 프로젝트 내부 ProjectedGraph를 Leiden 라이브러리용 Network로 변환한다.
            Network network = buildNetwork(graph, nNodes);

            /*
             * Leiden 내부에는 random 탐색이 존재한다.
             * 동일 runId에 동일 seed를 적용해 반복 실행 결과의 재현성을 높인다.
             */
            LeidenAlgorithm leiden =
                    new LeidenAlgorithm(new Random(resolveSeed(graph.getRunId())));

            leiden.setResolution(resolution);
            leiden.setNIterations(iterations);

            // 초기 clustering 객체를 생성하고 Leiden optimization을 수행한다.
            Clustering clustering = new Clustering(nNodes);
            leiden.improveClustering(network, clustering);

            /*
             * LeidenAlgorithm이 실제 최적화하는 CPM 품질함수를 직접 계산한다.
             *
             * 기존에는 Leiden 실행 결과를 Modularity로 다시 평가했기 때문에
             * 실제 optimization objective와 후보 선택 기준이 서로 달랐다.
             */
            double cpmQuality = leiden.calcQuality(network, clustering);

            return new DetectionResult(
                    new CommunityResult(clustering.getClusters()),
                    cpmQuality
            );

        } catch (ClusterException e) {
            throw e;

        } catch (Exception e) {
            log.error(
                    "[LEIDEN] detect failed. nNodes={}, edges={}, resolution={}, iterations={}",
                    graph.getNodes().size(),
                    graph.getEdges().size(),
                    resolution,
                    iterations,
                    e
            );

            throw new ClusterException(ClusterErrorCode.CLUSTER_LEIDEN_FAILED);
        }
    }

    /**
     * ProjectedGraph를 networkanalysis 라이브러리의 Network로 변환한다.
     */
    private Network buildNetwork(ProjectedGraph graph, int nNodes) {
        int edgeCount = graph.getEdges().size();

        LargeIntArray sources = new LargeIntArray(edgeCount);
        LargeIntArray targets = new LargeIntArray(edgeCount);
        LargeDoubleArray weights = new LargeDoubleArray(edgeCount);

        int cursor = 0;

        for (ProjectedEdge edge : graph.getEdges()) {
            sources.set(cursor, edge.getFromIndex());
            targets.set(cursor, edge.getToIndex());
            weights.set(cursor, edge.getWeight());
            cursor++;
        }

        /*
         * 기존 프로젝트와 동일하게 weighted graph 형태로 Network를 생성한다.
         */
        return new Network(
                nNodes,
                true,
                new LargeIntArray[]{sources, targets},
                weights,
                false,
                true
        );
    }

    /**
     * runId를 기반으로 deterministic seed를 생성한다.
     *
     * 같은 runId이면 같은 seed가 생성되므로
     * 같은 입력에 대한 Leiden 결과의 재현성을 높일 수 있다.
     */
    private long resolveSeed(String runId) {
        if (runId == null || runId.isBlank()) {
            return DEFAULT_LEIDEN_SEED;
        }

        return DEFAULT_LEIDEN_SEED + (runId.hashCode() & 0xffffffffL);
    }
}