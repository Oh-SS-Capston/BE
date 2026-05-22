package com.example.ossdoc.domain.run.cache.support;

/**
 * Redis READY 키에 저장하는 최소 payload 계약입니다.
 * 조회 서비스와 발행 서비스가 동일한 JSON 구조를 공유하도록 분리했습니다.
 */
public record ReadyPayload(
        String cacheKey,
        String sourceRunId
) {
}

