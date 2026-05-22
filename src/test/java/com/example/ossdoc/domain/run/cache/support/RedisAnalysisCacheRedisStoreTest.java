package com.example.ossdoc.domain.run.cache.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAnalysisCacheRedisStoreTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisAnalysisCacheRedisStore store;

    @BeforeEach
    void setUp() {
        store = new RedisAnalysisCacheRedisStore(stringRedisTemplate);
    }

    @Test
    void get은_redis_조회값을_optional로_반환한다() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("analysis:ready:key1")).thenReturn("{\"cacheKey\":\"key1\"}");

        Optional<String> result = store.get("analysis:ready:key1");

        assertThat(result).contains("{\"cacheKey\":\"key1\"}");
    }

    @Test
    void get_중_예외가_나도_empty로_폴백한다() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        Optional<String> result = store.get("analysis:ready:key2");

        assertThat(result).isEmpty();
    }

    @Test
    void set은_ttl이_정상일때_만료시간과_함께_저장한다() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        store.set("analysis:ready:key3", "{\"ok\":true}", Duration.ofHours(1));

        verify(valueOperations).set("analysis:ready:key3", "{\"ok\":true}", Duration.ofHours(1));
    }

    @Test
    void set은_ttl이_null이면_만료없이_저장한다() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        store.set("analysis:ready:key4", "{\"ok\":true}", null);

        verify(valueOperations).set("analysis:ready:key4", "{\"ok\":true}");
    }

    @Test
    void set_중_예외가_나도_상위로_전파하지_않는다() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // set 호출 시 예외 발생을 시뮬레이션합니다.
        org.mockito.Mockito.doThrow(new IllegalStateException("write fail"))
                .when(valueOperations).set("analysis:ready:key5", "{\"ok\":true}", Duration.ofMinutes(5));

        assertThatNoException()
                .isThrownBy(() -> store.set("analysis:ready:key5", "{\"ok\":true}", Duration.ofMinutes(5)));
    }

    @Test
    void delete_중_예외가_나도_상위로_전파하지_않는다() {
        org.mockito.Mockito.doThrow(new IllegalStateException("delete fail"))
                .when(stringRedisTemplate).delete("analysis:ready:key6");

        assertThatNoException()
                .isThrownBy(() -> store.delete("analysis:ready:key6"));
    }

    @Test
    void setIfAbsent_성공_시_true를_반환한다() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("analysis:lock:key1", "owner-1", Duration.ofMinutes(10)))
                .thenReturn(Boolean.TRUE);

        boolean acquired = store.setIfAbsent("analysis:lock:key1", "owner-1", Duration.ofMinutes(10));

        assertThat(acquired).isTrue();
    }

    @Test
    void setIfAbsent_경합_시_false를_반환한다() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("analysis:lock:key2", "owner-2", Duration.ofMinutes(10)))
                .thenReturn(Boolean.FALSE);

        boolean acquired = store.setIfAbsent("analysis:lock:key2", "owner-2", Duration.ofMinutes(10));

        assertThat(acquired).isFalse();
    }

    @Test
    void deleteIfValueMatches_삭제_성공_시_true를_반환한다() {
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                any()
        ))
                .thenReturn(1L);

        boolean released = store.deleteIfValueMatches("analysis:lock:key3", "owner-3");

        assertThat(released).isTrue();
        verify(stringRedisTemplate).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(java.util.Collections.singletonList("analysis:lock:key3")),
                eq("owner-3")
        );
    }

    @Test
    void deleteIfValueMatches_삭제_실패_시_false를_반환한다() {
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                any()
        ))
                .thenReturn(0L);

        boolean released = store.deleteIfValueMatches("analysis:lock:key4", "owner-4");

        assertThat(released).isFalse();
    }

    @Test
    void deleteIfValueMatches_중_예외가_나도_false로_폴백한다() {
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                any()
        ))
                .thenThrow(new IllegalStateException("unlock fail"));

        boolean released = store.deleteIfValueMatches("analysis:lock:key5", "owner-5");

        assertThat(released).isFalse();
    }
}
