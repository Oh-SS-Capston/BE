package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;
import org.springframework.stereotype.Service;

import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeidenCommunityService {
    private static final long DEFAULT_LEIDEN_SEED = 31_415_926L;

    /**
     * Leiden community detection을 수행한다.
     * 동일 run에서 결과 재현성을 높이기 위해 runId 기반 시드를 고정한다.
     */
    public CommunityResult detect(ProjectedGraph graph, double resolution, int iterations) {
        try {
            int nNodes = graph.getNodes().size();

            if (nNodes == 0) {
                throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
            }

            LargeIntArray sources = new LargeIntArray(graph.getEdges().size());
            LargeIntArray targets = new LargeIntArray(graph.getEdges().size());
            LargeDoubleArray weights = new LargeDoubleArray(graph.getEdges().size());

            int cursor = 0;
            for (ProjectedEdge edge : graph.getEdges()) {
                sources.set(cursor, edge.getFromIndex());
                targets.set(cursor, edge.getToIndex());
                weights.set(cursor, edge.getWeight());
                cursor++;
            }

            LargeIntArray[] edgeList = new LargeIntArray[]{sources, targets};

            Network network = new Network(
                    nNodes,
                    true,
                    edgeList,
                    weights,
                    false,
                    true
            );

            LeidenAlgorithm leiden = new LeidenAlgorithm(new Random(resolveSeed(graph.getRunId())));
            leiden.setResolution(resolution);
            leiden.setNIterations(iterations);

            Clustering clustering = new Clustering(nNodes);
            leiden.improveClustering(network, clustering);

            return new CommunityResult(clustering.getClusters());
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LEIDEN] detect failed. nNodes={}, edges={}, resolution={}, iterations={}",
                    graph.getNodes().size(), graph.getEdges().size(), resolution, iterations, e);
            throw new ClusterException(ClusterErrorCode.CLUSTER_LEIDEN_FAILED);
        }
    }

    /**
     * runId가 같으면 항상 동일한 시드를 만들고, runId가 비어있으면 기본 시드를 사용한다.
     */
    private long resolveSeed(String runId) {
        if (runId == null || runId.isBlank()) {
            return DEFAULT_LEIDEN_SEED;
        }
        return DEFAULT_LEIDEN_SEED + (runId.hashCode() & 0xffffffffL);
    }
}
