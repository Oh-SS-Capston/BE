package com.example.ossdoc.domain.cluster.model.ranking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    /*
     * rankings.json 보강 필드:
     * - LLM이 "어디를 설명하는지" 추적할 수 있도록 심볼 종류/소유자/소스 위치를 포함한다.
     */
    @JsonProperty("symbol_kind")
    private String symbolKind;

    @JsonProperty("owner_symbol")
    private String ownerSymbol;

    @JsonProperty("source_file")
    private String sourceFile;

    @JsonProperty("line_range")
    private LineRange lineRange;

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineRange {
        @JsonProperty("start_line")
        private Integer startLine;

        @JsonProperty("end_line")
        private Integer endLine;
    }
}
