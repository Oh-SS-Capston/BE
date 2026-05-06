package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.Wildcard;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * 타입 참조 표준 구조
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TypeRef(
        /**
         * 가능한 표준화된 이름(FQN 권장)
         * 예: java.util.List
         */
        @JsonProperty("raw")
        String raw,

        /**
         * 제네릭 인자들
         */
        @JsonProperty("args")
        List<TypeRef> args,

        /**
         * 배열 차원 (int[][] -> 2)
         */
        @JsonProperty("array_dim")
        Integer arrayDim,

        @JsonProperty("primitive")
        Boolean primitive,

        /**
         * 해결 여부 힌트
         */
        @JsonProperty("unresolved")
        Boolean unresolved,

        /**
         * AST 기준 원문 타입 표현
         * 예: List<String>
         */
        @JsonProperty("source_text")
        String sourceText,

        @JsonProperty("wildcard")
        Wildcard wildcard
) {
}