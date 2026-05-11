package com.example.ossdoc.domain.cluster.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProjectedEdge {
    private int fromIndex;
    private int toIndex;
    private double weight;
}
