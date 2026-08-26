package com.example.ossdoc.domain.run.cache.model;

import java.time.LocalDateTime;

/**
 * FAILED 캐시 쿨다운 조회 결과 모델입니다.
 *
 * coolingDown=true:
 * - 동일 cacheKey(또는 동일 repo+sha)의 직전 실패가 아직 쿨다운 구간입니다.
 * - retryAfter 이전에는 신규 분석 enqueue 대신 기존 실패 실행을 재사용할 수 있습니다.
 *
 * coolingDown=false:
 * - 즉시 재시도 가능한 상태입니다.
 */
public record AnalysisCacheFailedCooldownResult(
        boolean coolingDown,
        String cacheKey,
        String sourceRunId,
        LocalDateTime retryAfter,
        String reason
) {

    public static AnalysisCacheFailedCooldownResult active(
            String cacheKey,
            String sourceRunId,
            LocalDateTime retryAfter,
            String reason
    ) {
        return new AnalysisCacheFailedCooldownResult(true, cacheKey, sourceRunId, retryAfter, reason);
    }

    public static AnalysisCacheFailedCooldownResult inactive(String reason) {
        return new AnalysisCacheFailedCooldownResult(false, null, null, null, reason);
    }
}

