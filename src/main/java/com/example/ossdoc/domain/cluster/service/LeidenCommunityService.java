package com.example.ossdoc.domain.cluster.service;

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
        int nNodes = graph.getNodes().size();

        if (nNodes == 0) {
            return new CommunityResult(new int[0]);
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
                nNodes,  //노드 개수
                true,     //node의 가중치를 edge들의 가중치의 총합으로 설정 / false면 모든 node의 가중치를 1로
                edgeList,
                weights, //각 edge의 가중치 목록을 의미. EdgeWeightPolicy로 계산한 가중치 값이 여기에 해당
                false,    // edgeList가 이미 정렬된 상태인지를 의미. 현재는 무방향이기 때문에 false
                true      //무결성 검사
        );

        LeidenAlgorithm leiden = new LeidenAlgorithm();
        leiden.setResolution(resolution);
        leiden.setNIterations(iterations);

        Clustering clustering = new Clustering(nNodes);
        leiden.improveClustering(network, clustering);

        return new CommunityResult(clustering.getClusters());
    }
}