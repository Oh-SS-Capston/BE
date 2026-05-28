package com.example.ossdoc.global.llm.model;

/**
 * 코어 타입 시드 모델.
 */
public record CoreTypeSeed(
        String symbolId,
        String fqn,
        String packageName,
        String className,
        String filePath,
        String role,
        String usage,
        int importance,
        Integer startLine,
        Integer endLine
) {
    public CoreTypeSeed withImportance(int nextImportance) {
        return new CoreTypeSeed(
                symbolId,
                fqn,
                packageName,
                className,
                filePath,
                role,
                usage,
                nextImportance,
                startLine,
                endLine
        );
    }
}
