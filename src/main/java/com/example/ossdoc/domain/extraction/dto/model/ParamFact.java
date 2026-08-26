package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 메서드 파라미터 정보
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParamFact(
        @JsonProperty("name")
        String name,

        @JsonProperty("type_ref")
        TypeRef typeRef
) {
}
