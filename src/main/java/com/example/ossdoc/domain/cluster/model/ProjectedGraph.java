package com.example.ossdoc.domain.cluster.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ProjectedGraph {
    private String runId;
    private List<ProjectedNode> nodes;
    private List<ProjectedEdge> edges;

    private Map<String, Integer> nodeIndexMap;
}
