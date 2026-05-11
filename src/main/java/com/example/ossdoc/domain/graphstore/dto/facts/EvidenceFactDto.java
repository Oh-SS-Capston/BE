package com.example.ossdoc.domain.graphstore.dto.facts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvidenceFactDto {

    private String id;
    private String type;
    private String path;

    @JsonProperty("start_line")
    private Integer startLine;

    @JsonProperty("end_line")
    private Integer endLine;

    private String symbol;
    private String snippet;
    private String hash;
}