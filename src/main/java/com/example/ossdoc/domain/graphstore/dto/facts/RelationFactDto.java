package com.example.ossdoc.domain.graphstore.dto.facts;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelationFactDto {

    private String kind;

    @JsonAlias({"src_symbol", "from_symbol"})
    @JsonProperty("src_symbol")
    private String srcSymbol;

    @JsonAlias({"dst_symbol", "to_symbol"})
    @JsonProperty("dst_symbol")
    private String dstSymbol;

    @JsonProperty("dst_type_ref")
    private JsonNode dstTypeRef;

    private String origin;
    private String resolution;

    @JsonProperty("confidence_hint")
    private BigDecimal confidenceHint;

    @JsonProperty("evidence_ids")
    private List<String> evidenceIds = new ArrayList<>();
}