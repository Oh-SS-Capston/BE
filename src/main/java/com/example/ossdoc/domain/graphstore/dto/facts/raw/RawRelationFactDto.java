package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawRelationFactDto {

    private String kind;

    @JsonAlias({"from_symbol", "src", "from"})
    @JsonProperty("src_symbol")
    private String srcSymbol;

    @JsonAlias({"to_symbol", "dst", "to"})
    @JsonProperty("dst_symbol")
    private String dstSymbol;

    @JsonProperty("dst_raw_ref")
    private String dstRawRef;

    private String origin;

    private String derivation;

    private RawRelationResolutionDto resolution;

    @JsonProperty("call_site_line")
    private Integer callSiteLine;

    @JsonProperty("confidence_hint")
    private BigDecimal confidenceHint;

    @JsonProperty("evidence_ids")
    private List<String> evidenceIds = new ArrayList<>();

    private Map<String, Object> attrs = new LinkedHashMap<>();
}