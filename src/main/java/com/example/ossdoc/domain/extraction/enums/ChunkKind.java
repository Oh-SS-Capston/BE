package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 병렬 추출 청크 종류
 *
 * AST : .java 소스 기반 청크
 * ASM : .class 바이트코드 기반 청크
 */
public enum ChunkKind {
    AST("ast"),
    ASM("asm");

    private final String code;

    ChunkKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}