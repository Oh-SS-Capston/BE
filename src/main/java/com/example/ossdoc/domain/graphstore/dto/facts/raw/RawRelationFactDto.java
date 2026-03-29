package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawRelationFactDto {

    private String kind;

    @JsonAlias({"src_symbol", "from_symbol"})
    @JsonProperty("src_symbol")
    private String srcSymbol;

    @JsonAlias({"dst_symbol", "to_symbol"})
    @JsonProperty("dst_symbol")
    private String dstSymbol;

    @JsonProperty("dst_raw_ref")
    private String dstRawRef;

    private String origin;

    private RawRelationResolutionDto resolution;

    @JsonProperty("confidence_hint")
    private BigDecimal confidenceHint;

    @JsonProperty("evidence_ids")
    private List<String> evidenceIds = new ArrayList<>();
}