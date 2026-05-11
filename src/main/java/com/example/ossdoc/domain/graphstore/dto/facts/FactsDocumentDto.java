package com.example.ossdoc.domain.graphstore.dto.facts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class FactsDocumentDto {

    @JsonProperty("schema_version")
    private String schemaVersion;

    private Map<String, EvidenceFactDto> evidence = new LinkedHashMap<>();

    private List<SymbolFactDto> symbols = new ArrayList<>();

    private List<RelationFactDto> relations = new ArrayList<>();
}