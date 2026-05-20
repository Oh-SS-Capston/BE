package com.example.ossdoc.domain.run.cache.model;

/**
 * 분석 캐시 조회 결과 모델입니다.
 *
 * hit=true:
 * - 재사용 가능한 READY 캐시를 찾은 상태
 *
 * hit=false:
 * - 재사용 가능한 캐시가 없어 신규 분석으로 진행해야 하는 상태
 */
public record AnalysisCacheLookupResult(
        boolean hit,
        String cacheKey,
        String sourceRunId,
        String reason
) {

    public static AnalysisCacheLookupResult hit(String cacheKey, String sourceRunId, String reason) {
        return new AnalysisCacheLookupResult(true, cacheKey, sourceRunId, reason);
    }

    public static AnalysisCacheLookupResult miss(String reason) {
        return new AnalysisCacheLookupResult(false, null, null, reason);
    }
}
