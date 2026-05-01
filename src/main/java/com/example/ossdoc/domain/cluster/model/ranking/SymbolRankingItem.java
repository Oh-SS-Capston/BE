package com.example.ossdoc.domain.cluster.model.ranking;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SymbolRankingItem {
    private int rank;
    private String symbolId;
    private String qualifiedName;
    private String subsystemId;
    private double score;
    private double structuralScore;
    private double bridgeScore;
    private double apiScore;
    private double evidenceScore;
    private double subsystemCentralityScore;
}