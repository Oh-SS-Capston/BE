package com.example.ossdoc.global.llm.model;

/**
 * 심볼 소스 위치 보강용 앵커 모델.
 */
public record SourceAnchor(
        String symbolId,
        String fqn,
        String sourceFile,
        Integer startLine,
        Integer endLine,
        double confidence
) {
}
