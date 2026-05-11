package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawFactsDocumentDto {

    @JsonProperty("schema_version")
    private String schemaVersion;

    private Map<String, RawEvidenceFactDto> evidence = new LinkedHashMap<>();

    private RawSymbolTableDto symbols = new RawSymbolTableDto();

    private RawRelationTableDto relations = new RawRelationTableDto();

    @JsonProperty("observations")
    private JsonNode observations;
}
