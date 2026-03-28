package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 병렬 추출 결과 병합 단계
 *
 * CHUNK  : worker local 결과
 * ROOT   : sourceRoot / bytecodeRoot 단위 결과
 * MODULE : module 단위 결과
 * FINAL  : 최종 composer 입력 결과
 */
public enum MergeStage {
    CHUNK("chunk"),
    ROOT("root"),
    MODULE("module"),
    FINAL("final");

    private final String code;

    MergeStage(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}