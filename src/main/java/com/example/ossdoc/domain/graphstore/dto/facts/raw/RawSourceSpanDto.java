package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawSourceSpanDto {

    @JsonProperty("start_line")
    private Integer startLine;

    @JsonProperty("start_col")
    private Integer startCol;

    @JsonProperty("end_line")
    private Integer endLine;

    @JsonProperty("end_col")
    private Integer endCol;
}