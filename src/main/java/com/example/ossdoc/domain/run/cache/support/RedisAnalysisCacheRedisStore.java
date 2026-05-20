package com.example.ossdoc.domain.run.cache.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 실제 Redis와 통신하는 분석 캐시 저장소입니다.
 *
 * 설계 의도:
 * - Redis가 정상일 때는 빠른 캐시 조회/동기화를 수행합니다.
 * - Redis가 일시 장애여도 파이프라인 본 흐름은 계속 진행되도록 안전 폴백합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ossdoc.analysis-cache", name = "redis-enabled", havingValue = "true")
public class RedisAnalysisCacheRedisStore implements AnalysisCacheRedisStore {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(stringRedisTemplate.opsForValue().get(key));
        } catch (Exception e) {
            // Redis 조회 실패는 캐시 miss로 폴백해 서비스 연속성을 우선합니다.
            log.warn("[CACHE][REDIS] get failed. key={}", abbreviate(key), e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        try {
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                // TTL 비정상 입력 시에도 저장 자체는 수행해 데이터 유실을 줄입니다.
                stringRedisTemplate.opsForValue().set(key, value);
                return;
            }
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            // 동기화 실패는 경고만 남기고 본 처리 흐름은 계속 진행합니다.
            log.warn("[CACHE][REDIS] set failed. key={}, ttl={}", abbreviate(key), ttl, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            // stale 정리 실패도 치명 오류로 승격하지 않고 다음 조회에서 복구되게 둡니다.
            log.warn("[CACHE][REDIS] delete failed. key={}", abbreviate(key), e);
        }
    }

    private String abbreviate(String key) {
        if (key == null || key.isBlank()) {
            return "<empty>";
        }
        return key.length() <= 24 ? key : key.substring(0, 24);
    }
}
