package com.example.ossdoc.domain.cluster.model.ranking;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SubsystemRankingItem {
    private int rank;
    private String subsystemId;
    private String name;
    private double score;
}