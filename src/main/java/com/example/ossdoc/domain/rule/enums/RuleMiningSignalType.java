package com.example.ossdoc.domain.rule.enums;

public enum RuleMiningSignalType {

    // Evidence.snippet 휴리스틱 기반
    IF_CONDITION,
    THROW_STATEMENT,
    RETURN_STATEMENT,
    ERROR_RESPONSE,

    // GraphStore Edge 기반
    METHOD_CALL,
    OBJECT_CREATION,
    FIELD_ACCESS,
    FIELD_WRITE,
    ENUM_STATE_CHANGE,
    THROWS_DECLARATION,
    ANNOTATION,

    // METHOD_CALL 분류 기반
    REPOSITORY_SAVE,
    REPOSITORY_UPDATE,
    REPOSITORY_DELETE,
    ASSERTION_CALL,
    REQUIRE_CALL,

    // Annotation / Graph 패턴 기반
    TRANSACTION_BOUNDARY
}