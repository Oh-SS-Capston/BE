package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * relation 해석 상태
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationResolution(
        @JsonProperty("status")
        ResolutionStatus status,

        @JsonProperty("reason")
        String reason
) {
}