package com.example.ossdoc.domain.run.cache.support;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 미연동 환경을 위한 no-op 구현체입니다.
 *
 * 동작 정책:
 * - get: 항상 miss 처리
 * - set/delete: 아무 동작 없음
 *
 * 이렇게 두는 이유:
 * - 의존성/인프라 준비 전에도 서비스 로직을 먼저 검증할 수 있고,
 * - 이후 실제 Redis 구현체를 추가해도 서비스 코드는 그대로 재사용할 수 있습니다.
 */
@Component
@ConditionalOnMissingBean(AnalysisCacheRedisStore.class)
public class NoopAnalysisCacheRedisStore implements AnalysisCacheRedisStore {

    @Override
    public Optional<String> get(String key) {
        return Optional.empty();
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        // no-op
    }

    @Override
    public void delete(String key) {
        // no-op
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        // Redis 미연동 환경에서는 중복 실행 방지를 강제하지 않고 항상 획득 성공으로 처리한다.
        return true;
    }

    @Override
    public boolean deleteIfValueMatches(String key, String expectedValue) {
        // no-op
        return true;
    }
}
