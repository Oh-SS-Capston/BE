package com.example.ossdoc.domain.run.cache.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "ossdoc.analysis-cache",
        name = "redis-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoopAnalysisCacheRedisStore
        implements AnalysisCacheRedisStore {

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
        return true;
    }

    @Override
    public boolean deleteIfValueMatches(
            String key,
            String expectedValue
    ) {
        return true;
    }
}