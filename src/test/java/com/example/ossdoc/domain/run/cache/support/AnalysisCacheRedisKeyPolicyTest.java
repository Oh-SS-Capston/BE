package com.example.ossdoc.domain.run.cache.support;

import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCacheRedisKeyPolicyTest {

    @Test
    void ready와_lock_키_형식이_정해진_규칙으로_생성된다() {
        AnalysisCacheProperties properties = new AnalysisCacheProperties();
        properties.setRedisReadyKeyPrefix("analysis:ready:");
        properties.setRedisLockKeyPrefix("analysis:lock:");

        AnalysisCacheRedisKeyPolicy policy = new AnalysisCacheRedisKeyPolicy(properties);

        assertThat(policy.readyKey("abc123")).isEqualTo("analysis:ready:abc123");
        assertThat(policy.lockKey("abc123")).isEqualTo("analysis:lock:abc123");
    }

    @Test
    void ttl은_설정값을_duration으로_변환한다() {
        AnalysisCacheProperties properties = new AnalysisCacheProperties();
        properties.setRedisReadyTtlHours(48L);
        properties.setRedisLockTtlMinutes(15L);

        AnalysisCacheRedisKeyPolicy policy = new AnalysisCacheRedisKeyPolicy(properties);

        assertThat(policy.readyTtl()).isEqualTo(Duration.ofHours(48L));
        assertThat(policy.lockTtl()).isEqualTo(Duration.ofMinutes(15L));
    }

    @Test
    void 잘못된_빈값_입력에도_안전한_fallback_키가_생성된다() {
        AnalysisCacheProperties properties = new AnalysisCacheProperties();
        properties.setRedisReadyKeyPrefix(" ");
        properties.setRedisLockKeyPrefix(null);
        properties.setRedisReadyTtlHours(0L);
        properties.setRedisLockTtlMinutes(0L);

        AnalysisCacheRedisKeyPolicy policy = new AnalysisCacheRedisKeyPolicy(properties);

        assertThat(policy.readyKey(" ")).isEqualTo("analysis:unknown:unknown");
        assertThat(policy.lockKey(null)).isEqualTo("analysis:unknown:unknown");
        assertThat(policy.readyTtl()).isEqualTo(Duration.ofHours(1L));
        assertThat(policy.lockTtl()).isEqualTo(Duration.ofMinutes(1L));
    }
}
