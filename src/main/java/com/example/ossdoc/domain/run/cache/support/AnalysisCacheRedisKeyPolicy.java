package com.example.ossdoc.domain.run.cache.support;

import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 분석 캐시용 Redis 키 네이밍/TTL 정책을 한 곳에 모읍니다.
 *
 * 왜 필요한가:
 * - 서비스마다 문자열을 직접 조합하면 오타/규칙 불일치가 쉽게 발생합니다.
 * - 키 prefix와 TTL 변경 지점을 단일 클래스로 고정하면 운영 조정이 안전해집니다.
 */
@Component
@RequiredArgsConstructor
public class AnalysisCacheRedisKeyPolicy {

    private final AnalysisCacheProperties properties;

    /**
     * READY 캐시 조회 키를 생성합니다.
     * 형태: {readyPrefix}:{cacheKey}
     * 예: analysis:ready:ab12...
     */
    public String readyKey(String cacheKey) {
        return normalizePrefix(properties.getRedisReadyKeyPrefix()) + ":" + normalizeCacheKey(cacheKey);
    }

    /**
     * 분석 중복 실행 방지용 LOCK 키를 생성합니다.
     * 형태: {lockPrefix}:{cacheKey}
     * 예: analysis:lock:ab12...
     */
    public String lockKey(String cacheKey) {
        return normalizePrefix(properties.getRedisLockKeyPrefix()) + ":" + normalizeCacheKey(cacheKey);
    }

    /**
     * READY 키 TTL을 Duration으로 반환합니다.
     */
    public Duration readyTtl() {
        return Duration.ofHours(Math.max(1L, properties.getRedisReadyTtlHours()));
    }

    /**
     * LOCK 키 TTL을 Duration으로 반환합니다.
     */
    public Duration lockTtl() {
        return Duration.ofMinutes(Math.max(1L, properties.getRedisLockTtlMinutes()));
    }

    /**
     * prefix의 마지막 ':' 중복을 제거해 키 포맷을 안정화합니다.
     */
    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "analysis:unknown";
        }
        String normalized = prefix.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * cacheKey가 비어도 키 생성이 깨지지 않도록 fallback을 둡니다.
     */
    private String normalizeCacheKey(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return "unknown";
        }
        return cacheKey.trim();
    }
}
