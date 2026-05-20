package com.example.ossdoc.domain.run.cache.support;

import java.time.Duration;
import java.util.Optional;

/**
 * 분석 캐시 조회용 Redis 접근 포트입니다.
 *
 * 왜 인터페이스로 분리했는가:
 * - W05 단계에서는 조회 규칙을 먼저 고정하고,
 * - 실제 Redis 클라이언트 연동(W06 이후)은 구현체로 분리해 점진 적용하기 위함입니다.
 */
public interface AnalysisCacheRedisStore {

    Optional<String> get(String key);

    void set(String key, String value, Duration ttl);

    void delete(String key);
}
