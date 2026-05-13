package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 모듈 참조 정보
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModuleRef(
        @JsonProperty("name")
        String name,

        @JsonProperty("path")
        String path
) {
}
