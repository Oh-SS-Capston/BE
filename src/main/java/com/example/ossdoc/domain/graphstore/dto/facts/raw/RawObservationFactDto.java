package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawObservationFactDto {

    private String kind;

    @JsonProperty("site_symbol")
    private String siteSymbol;

    @JsonProperty("target_symbol")
    private String targetSymbol;

    @JsonProperty("target_type_ref")
    private JsonNode targetTypeRef;

    private String note;

    @JsonProperty("evidence_ids")
    private List<String> evidenceIds = new ArrayList<>();

    private String origin;

    @JsonProperty("confidence_hint")
    private Double confidenceHint;

    private JsonNode attrs;
}
