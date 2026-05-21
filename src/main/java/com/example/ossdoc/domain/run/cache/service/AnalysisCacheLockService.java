package com.example.ossdoc.domain.run.cache.service;

import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisKeyPolicy;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * W09: 동일 분석 요청의 중복 실행을 줄이기 위한 Redis 분산 락 서비스입니다.
 *
 * 핵심 정책:
 * - lock key는 cacheKey 단위로 생성한다.
 * - 락 획득 성공 시에만 신규 분석 enqueue를 허용한다.
 * - 락은 TTL 기반으로 자동 만료되어 비정상 종료 시에도 영구 교착을 피한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisCacheLockService {

    private final AnalysisCacheRedisKeyPolicy redisKeyPolicy;
    private final AnalysisCacheRedisStore redisStore;

    /**
     * cacheKey 단위 락 획득을 시도합니다.
     *
     * @return 획득 성공 여부
     */
    public boolean tryAcquire(String cacheKey, String ownerToken) {
        String lockKey = redisKeyPolicy.lockKey(cacheKey);
        boolean acquired = redisStore.setIfAbsent(lockKey, ownerToken, redisKeyPolicy.lockTtl());
        if (acquired) {
            log.info("[CACHE][LOCK] acquired. cacheKey={}", abbreviate(cacheKey));
        } else {
            log.info("[CACHE][LOCK] contention. cacheKey={}", abbreviate(cacheKey));
        }
        return acquired;
    }

    /**
     * 본 요청(ownerToken)이 락 소유자일 때만 락을 해제합니다.
     */
    public void releaseIfOwned(String cacheKey, String ownerToken) {
        String lockKey = redisKeyPolicy.lockKey(cacheKey);
        boolean released = redisStore.deleteIfValueMatches(lockKey, ownerToken);
        if (released) {
            log.info("[CACHE][LOCK] released. cacheKey={}", abbreviate(cacheKey));
        } else {
            log.info("[CACHE][LOCK] release skipped. cacheKey={}, reason=NOT_OWNER_OR_EXPIRED", abbreviate(cacheKey));
        }
    }

    private String abbreviate(String key) {
        if (key == null || key.isBlank()) {
            return "<empty>";
        }
        return key.length() <= 12 ? key : key.substring(0, 12);
    }
}
