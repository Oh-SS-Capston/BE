package com.example.ossdoc.domain.graphstore.enums;

/**
 * GraphStore에 저장 가능한 관계 종류.
 *
 * 구조 관계와 Observation resolver가 생성하는 의미 관계를
 * 동일한 Edge 모델로 저장한다.
 */
public enum EdgeType {
    // Structural
    CONTAINS,
    EXTENDS,
    IMPLEMENTS,
    OVERRIDES,
    HAS_FIELD,
    ACCESSES_FIELD,
    PARAM,
    RETURNS,
    THROWS,
    CALLS,
    CREATES,
    ANNOTATED_WITH,

    // Semantic / framework
    HANDLES_ENDPOINT,
    DECLARES_BEAN,
    CONFIGURES_BEAN,
    INJECTS,
    PUBLISHES_EVENT,
    LISTENS_EVENT,
    PROVIDES_SPI,
    LOADS_SERVICE,
    REFLECTS_TYPE,
    REFLECTS_METHOD,
    REFLECTS_FIELD,
    REFLECTS_CONSTRUCTOR
}
