package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import lombok.RequiredArgsConstructor;
import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LeidenCommunityService { //Leiden 실행 서비스

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

            LeidenAlgorithm leiden = new LeidenAlgorithm();
            leiden.setResolution(resolution);
            leiden.setNIterations(iterations);

            Clustering clustering = new Clustering(nNodes);
            leiden.improveClustering(network, clustering);

            return new CommunityResult(clustering.getClusters());
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_LEIDEN_FAILED);
        }
    }
}