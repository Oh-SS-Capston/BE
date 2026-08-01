package com.example.ossdoc.domain.run.cache.support;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 분석 캐시 조회용 Redis 접근 포트입니다.
 * <p>
 * 왜 인터페이스로 분리했는가:
 * - W05 단계에서는 조회 규칙을 먼저 고정하고,
 * - 실제 Redis 클라이언트 연동(W06 이후)은 구현체로 분리해 점진 적용하기 위함입니다.
 */
public interface AnalysisCacheRedisStore {

    Optional<String> get(String key);

    void set(String key, String value, Duration ttl);

    void delete(String key);

    /**
     * key가 없을 때만 값을 저장합니다(SET NX).
     *
     * @return 저장 성공(true), 이미 key 존재/실패(false)
     */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /**
     * key 현재 값이 expectedValue와 일치할 때만 삭제합니다.
     *
     * @return 삭제 성공(true), 값 불일치/미존재/실패(false)
     */
    boolean deleteIfValueMatches(String key, String expectedValue);
}
