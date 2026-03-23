package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * 메서드/생성자/필드 시그니처 표준 구조
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignatureFact(
        /**
         * 메서드/생성자 파라미터 타입들
         */
        @JsonProperty("params")
        List<TypeRef> params,

        /**
         * 메서드 반환 타입
         */
        @JsonProperty("returns")
        TypeRef returns,

        /**
         * throws 선언 타입들
         */
        @JsonProperty("throws")
        List<TypeRef> throwsTypes,

        /**
         * 필드 타입
         */
        @JsonProperty("field_type")
        TypeRef fieldType
) {
}