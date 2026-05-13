package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawFactsDocumentDto {

    @JsonProperty("schema_version")
    private String schemaVersion;

    private List<RawEvidenceFactDto> evidence = new ArrayList<>();

    private RawSymbolTableDto symbols = new RawSymbolTableDto();

    private RawRelationTableDto relations = new RawRelationTableDto();

    @JsonProperty("observations")
    private RawObservationTableDto observations = new RawObservationTableDto();
}
