package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawRelationResolutionDto {

    private String status;
    private String reason;
}