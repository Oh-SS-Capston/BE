package com.example.ossdoc.domain.run.cache.support;

import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 실제로 선택된 Redis 저장소 구현체를 로그로 남깁니다.
 *
 * 왜 필요한가:
 * - redis-enabled 설정값과 실제 등록된 빈이 일치하는지 즉시 확인할 수 있습니다.
 * - 운영/로컬에서 Noop/실 Redis 전환 상태를 빠르게 검증할 수 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisCacheRedisStoreStartupLogger {

    private final AnalysisCacheRedisStore redisStore;
    private final AnalysisCacheProperties analysisCacheProperties;

    @PostConstruct
    void logSelectedRedisStore() {
        log.info(
                "[CACHE][REDIS] store selected. redisEnabled={}, implementation={}",
                analysisCacheProperties.isRedisEnabled(),
                redisStore.getClass().getSimpleName()
        );
    }
}

