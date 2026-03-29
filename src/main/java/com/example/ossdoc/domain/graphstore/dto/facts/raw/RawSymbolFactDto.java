package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonAlias;
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
public class RawSymbolFactDto {

    private String symbol;
    private String name;
    private String kind;
    private String access;
    private String origin;

    @JsonProperty("qualified_name")
    private String qualifiedName;

    @JsonAlias({"owner_type_symbol", "ownerSymbol"})
    @JsonProperty("owner_type_symbol")
    private String ownerTypeSymbol;

    @JsonProperty("package_symbol")
    private String packageSymbol;

    private String module;

    @JsonProperty("source_file")
    private String sourceFile;

    @JsonProperty("evidence_ids")
    private List<String> evidenceIds = new ArrayList<>();

    private List<String> modifiers = new ArrayList<>();

    private JsonNode signature;
}