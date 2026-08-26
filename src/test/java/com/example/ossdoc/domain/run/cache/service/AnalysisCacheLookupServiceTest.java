package com.example.ossdoc.domain.run.cache.service;

import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheFailedCooldownResult;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.repository.AnalysisCacheRepository;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisKeyPolicy;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisStore;
import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import com.example.ossdoc.global.llm.enums.LlmProvider;
import com.example.ossdoc.global.llm.service.support.LlmChatClient;
import com.example.ossdoc.global.llm.service.support.LlmChatClientResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisCacheLookupServiceTest {

    @Mock
    private AnalysisCacheRepository analysisCacheRepository;

    @Mock
    private AnalysisCacheRedisStore redisStore;

    private AnalysisCacheLookupService lookupService;

    @BeforeEach
    void setUp() {
        AnalysisCacheProperties properties = new AnalysisCacheProperties();
        properties.setRedisReadyKeyPrefix("analysis:ready");
        properties.setRedisLockKeyPrefix("analysis:lock");
        AnalysisCacheRedisKeyPolicy keyPolicy = new AnalysisCacheRedisKeyPolicy(properties);
        lookupService = new AnalysisCacheLookupService(
                analysisCacheRepository,
                keyPolicy,
                redisStore,
                new ObjectMapper(),
                new LlmChatClientResolver(List.of(new StubOllamaChatClient()), "ollama")
        );
    }

    @Test
    void redisHitAndDbReady_returnsHit() {
        String cacheKey = "cache-key-1";
        AnalysisCache ready = readyCache(cacheKey, "run_1");

        when(redisStore.get("analysis:ready:" + cacheKey))
                .thenReturn(Optional.of("{\"cacheKey\":\"cache-key-1\",\"sourceRunId\":\"run_1\"}"));
        when(analysisCacheRepository.findByKeyAndStatus(cacheKey, AnalysisCacheStatus.READY))
                .thenReturn(Optional.of(ready));

        AnalysisCacheLookupResult result = lookupService.lookupReady(
                cacheKey,
                "github://apache/commons-cli",
                "e717fd63",
                "OLLAMA"
        );

        assertThat(result.hit()).isTrue();
        assertThat(result.sourceRunId()).isEqualTo("run_1");
        assertThat(result.reason()).isEqualTo("REDIS_HIT_DB_CONFIRMED");
        assertThat(ready.getHitCount()).isEqualTo(1L);
        verify(analysisCacheRepository, never())
                .findLatestByRepoAndCommitAndProviderAndStatus(any(), any(), any(), any(), any());
    }

    @Test
    void redisHitButDbMismatch_syncsRedisFromDb() {
        String inputCacheKey = "cache-key-input";
        AnalysisCache dbReady = readyCache("cache-key-db", "run_db");

        when(redisStore.get("analysis:ready:" + inputCacheKey))
                .thenReturn(Optional.of("{\"cacheKey\":\"cache-key-input\",\"sourceRunId\":\"run_old\"}"));
        when(analysisCacheRepository.findByKeyAndStatus(inputCacheKey, AnalysisCacheStatus.READY))
                .thenReturn(Optional.empty());
        when(analysisCacheRepository.findLatestByRepoAndCommitAndProviderAndStatus(
                "github://apache/commons-cli",
                "e717fd63",
                AnalysisCacheStatus.READY,
                "OLLAMA",
                "OLLAMA"
        )).thenReturn(Optional.of(dbReady));

        AnalysisCacheLookupResult result = lookupService.lookupReady(
                inputCacheKey,
                "github://apache/commons-cli",
                "e717fd63",
                "OLLAMA"
        );

        assertThat(result.hit()).isTrue();
        assertThat(result.cacheKey()).isEqualTo("cache-key-db");
        assertThat(result.sourceRunId()).isEqualTo("run_db");
        assertThat(result.reason()).isEqualTo("DB_HIT_CACHE_KEY_MISMATCH_SYNCED");
        verify(redisStore).delete("analysis:ready:" + inputCacheKey);
        verify(redisStore).set(eq("analysis:ready:cache-key-db"), any(), any());
    }

    @Test
    void redisAndDbMiss_returnsMiss() {
        String cacheKey = "cache-key-3";

        when(redisStore.get("analysis:ready:" + cacheKey))
                .thenReturn(Optional.empty());
        when(analysisCacheRepository.findLatestByRepoAndCommitAndProviderAndStatus(
                "github://apache/commons-cli",
                "e717fd63",
                AnalysisCacheStatus.READY,
                "OLLAMA",
                "OLLAMA"
        )).thenReturn(Optional.empty());

        AnalysisCacheLookupResult result = lookupService.lookupReady(
                cacheKey,
                "github://apache/commons-cli",
                "e717fd63",
                "OLLAMA"
        );

        assertThat(result.hit()).isFalse();
        assertThat(result.reason()).isEqualTo("CACHE_MISS");
        verify(redisStore, never()).set(any(), any(), any());
    }

    @Test
    void failedCooldownActive_returnsCoolingDown() {
        String cacheKey = "cache-key-failed";
        AnalysisCache failed = failedCache(cacheKey, "run_failed_1", 5);

        when(analysisCacheRepository.findByKeyAndStatus(cacheKey, AnalysisCacheStatus.FAILED))
                .thenReturn(Optional.of(failed));

        AnalysisCacheFailedCooldownResult result = lookupService.lookupFailedCooldown(
                cacheKey,
                "github://apache/commons-cli",
                "e717fd63"
        );

        assertThat(result.coolingDown()).isTrue();
        assertThat(result.cacheKey()).isEqualTo(cacheKey);
        assertThat(result.sourceRunId()).isEqualTo("run_failed_1");
        assertThat(result.retryAfter()).isEqualTo(failed.getExpiresAt());
        assertThat(result.reason()).isEqualTo("FAILED_COOLDOWN_BY_KEY");
    }

    @Test
    void failedCooldownExpired_returnsInactive() {
        String cacheKey = "cache-key-failed-expired";
        AnalysisCache failed = failedCache(cacheKey, "run_failed_2", -1);

        when(analysisCacheRepository.findByKeyAndStatus(cacheKey, AnalysisCacheStatus.FAILED))
                .thenReturn(Optional.of(failed));
        when(analysisCacheRepository.findLatestByRepoAndCommitAndStatus(
                "github://apache/commons-cli",
                "e717fd63",
                AnalysisCacheStatus.FAILED
        )).thenReturn(Optional.of(failed));

        AnalysisCacheFailedCooldownResult result = lookupService.lookupFailedCooldown(
                cacheKey,
                "github://apache/commons-cli",
                "e717fd63"
        );

        assertThat(result.coolingDown()).isFalse();
        assertThat(result.reason()).isEqualTo("FAILED_COOLDOWN_NOT_ACTIVE");
    }

    private AnalysisCache readyCache(String cacheKey, String sourceRunId) {
        AnalysisCache cache = new AnalysisCache(
                cacheKey,
                "github://apache/commons-cli",
                "e717fd63"
        );
        cache.markReady(
                sourceRunId,
                new ObjectMapper().createObjectNode().put("artifactId", 101L),
                "quality-hash",
                null
        );
        return cache;
    }

    private AnalysisCache failedCache(String cacheKey, String sourceRunId, long minutesOffset) {
        AnalysisCache cache = new AnalysisCache(
                cacheKey,
                "github://apache/commons-cli",
                "e717fd63"
        );
        cache.markFailed(
                sourceRunId,
                java.time.LocalDateTime.now().plusMinutes(minutesOffset)
        );
        return cache;
    }

    /** provider 축만 통과시키기 위한 스텁. 실제 LLM 호출은 이 테스트 범위가 아니다. */
    private record StubOllamaChatClient() implements LlmChatClient {
        @Override
        public String resolvePrimaryModel() {
            return "stub-model";
        }

        @Override
        public LlmProvider provider() {
            return LlmProvider.OLLAMA;
        }

        @Override
        public JsonNode call(String stepName, String systemPrompt, String userMessage, int maxTokens) {
            throw new UnsupportedOperationException("stub");
        }
    }
}

