package com.example.ossdoc.global.llm.model;

/**
 * 랭킹 기반 심볼 점수 모델.
 */
public record RankingSymbol(
        String symbolId,
        String qualifiedName,
        double score,
        double apiScore
) {
}
