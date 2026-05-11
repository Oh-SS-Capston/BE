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
         * 메서드/생성자 파라미터
         */
        @JsonProperty("params")
        List<ParamFact> params,

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
        TypeRef fieldType,

        /**
         * Javadoc 전체 텍스트 (설명 + 블록 태그). publicapi 탐지에 사용.
         * AST extractor만 채운다.
         */
        @JsonProperty("javadoc")
        String javadoc,

        /**
         * sealed class 여부. ExtensionPoint 제외 필터에 사용.
         * true이면 JSON에 기록, false/null이면 NON_NULL로 생략.
         */
        @JsonProperty("sealed")
        Boolean sealed
) {
}