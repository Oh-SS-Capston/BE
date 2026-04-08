package com.example.ossdoc.domain.graphstore.dto.facts;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SymbolFactDto {

    private String symbol;
    private String name;
    private String kind;
    private String access;
    private List<String> modifiers = new ArrayList<>();
    private String origin;

    @JsonAlias({"owner_type_symbol", "ownerSymbol"})
    @JsonProperty("owner_type_symbol")
    private String ownerTypeSymbol;

    @JsonProperty("source_file")
    private String sourceFile;

    @JsonProperty("evidence_ids")
    private List<String> evidenceIds = new ArrayList<>();
    private JsonNode signature; // facts가 signature를 직접 주면 사용, 아니면 빈 object로 저장
}