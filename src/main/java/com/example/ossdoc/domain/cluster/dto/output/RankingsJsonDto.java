package com.example.ossdoc.domain.cluster.dto.output;

import com.example.ossdoc.domain.cluster.model.ranking.SubsystemRankingItem;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Builder
public class RankingsJsonDto {
    private String schemaVersion;
    private String runId;
    private OffsetDateTime generatedAt;
    private List<SymbolRankingItem> symbolRankings;
    private List<SubsystemRankingItem> subsystemRankings;
}