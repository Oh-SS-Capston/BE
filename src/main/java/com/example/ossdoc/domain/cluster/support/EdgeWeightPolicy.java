package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class EdgeWeightPolicy {
    private double baseWeight(EdgeType type){
        return switch(type){
            case EXTENDS, IMPLEMENTS -> 3.0;
            case HAS_FIELD, PARAM, RETURNS, THROWS -> 2.0;
            case CALLS, OVERRIDES, ACCESSES_FIELD -> 1.5;
            case ANNOTATED_WITH -> 0.7;
            case CONTAINS -> 0.5;
        };
    }
    private double resolutionFactor(ResolutionStatus status){
        return switch (status){
            case RESOLVED -> 1.0;
            case PARTIAL -> 0.7;
            case UNRESOLVED -> 0.3;
        };
    }
    private double confidence(BigDecimal confidence){
        return confidence == null ? 1.0 : confidence.doubleValue();
    }
    public double weightOf(Edge edge){
        double base = baseWeight(edge.getEdgeType());
        double resolutionFactor = resolutionFactor(edge.getResolution());
        double confidence = confidence(edge.getConfidence());
        return base * resolutionFactor * confidence;
    }

}
