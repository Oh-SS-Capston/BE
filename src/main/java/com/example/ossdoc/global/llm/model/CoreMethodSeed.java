package com.example.ossdoc.global.llm.model;

/**
 * 코어 메서드 시드 모델.
 */
public record CoreMethodSeed(
        String symbolId,
        String classSymbolId,
        String classFqn,
        String className,
        String methodName,
        String fqn,
        String filePath,
        int importance,
        String signatureHint,
        String summarySeed,
        String scenarioHint,
        Integer startLine,
        Integer endLine
) {
}
