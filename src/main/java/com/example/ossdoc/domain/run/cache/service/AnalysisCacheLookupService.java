package com.example.ossdoc.domain.run.cache.service;

import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.repository.AnalysisCacheRepository;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisKeyPolicy;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisStore;
import com.example.ossdoc.domain.run.cache.support.ReadyPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * W05: Redis -> DB 순으로 READY 캐시를 조회하는 서비스입니다.
 *
 * 조회 정책:
 * 1) Redis READY 키를 먼저 조회
 * 2) Redis hit여도 DB READY 레코드로 최소 무결성 검증
 * 3) 불일치면 Redis 정리 후 DB 재조회
 * 4) DB에서 READY를 찾으면 Redis를 재동기화하고 hit 처리
 * 5) 끝까지 없으면 miss 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisCacheLookupService {

    private final AnalysisCacheRepository analysisCacheRepository;
    private final AnalysisCacheRedisKeyPolicy redisKeyPolicy;
    private final AnalysisCacheRedisStore redisStore;
    private final ObjectMapper objectMapper;

    @Transactional
    public AnalysisCacheLookupResult lookupReady(String cacheKey, String repoUrlNorm, String commitSha) {
        String readyKey = redisKeyPolicy.readyKey(cacheKey);

        Optional<String> redisPayload = redisStore.get(readyKey);
        if (redisPayload.isPresent()) {
            Optional<AnalysisCache> byKeyReady = analysisCacheRepository.findByCacheKeyAndStatus(
                    cacheKey,
                    AnalysisCacheStatus.READY
            );
            if (byKeyReady.isPresent() && isUsableReady(byKeyReady.get())) {
                AnalysisCache cache = byKeyReady.get();
                // Redis payload와 DB 레코드가 다르면 즉시 재동기화해 다음 조회부터 안정화합니다.
                if (!matchesPayload(redisPayload.get(), cache)) {
                    syncRedisReady(cache);
                }
                cache.recordHit(LocalDateTime.now());
                return AnalysisCacheLookupResult.hit(
                        cache.getCacheKey(),
                        cache.getSourceRunId(),
                        "REDIS_HIT_DB_CONFIRMED"
                );
            }

            // Redis는 hit인데 DB READY가 없으면 stale 키이므로 정리하고 DB 재조회로 폴백합니다.
            redisStore.delete(readyKey);
            log.info("[CACHE] stale redis ready key removed. key={}", abbreviate(cacheKey));
        }

        Optional<AnalysisCache> dbReady = analysisCacheRepository
                .findTopByRepoUrlNormAndCommitShaAndStatusOrderByUpdatedAtDesc(
                        repoUrlNorm,
                        commitSha,
                        AnalysisCacheStatus.READY
                );

        if (dbReady.isPresent() && isUsableReady(dbReady.get())) {
            AnalysisCache cache = dbReady.get();
            syncRedisReady(cache);
            cache.recordHit(LocalDateTime.now());

            String reason = cache.getCacheKey().equals(cacheKey)
                    ? "DB_HIT_REDIS_SYNCED"
                    : "DB_HIT_CACHE_KEY_MISMATCH_SYNCED";

            return AnalysisCacheLookupResult.hit(
                    cache.getCacheKey(),
                    cache.getSourceRunId(),
                    reason
            );
        }

        return AnalysisCacheLookupResult.miss("CACHE_MISS");
    }

    private boolean isUsableReady(AnalysisCache cache) {
        return cache.getStatus() == AnalysisCacheStatus.READY
                && cache.getSourceRunId() != null
                && !cache.getSourceRunId().isBlank()
                && cache.getArtifactBundleJson() != null;
    }

    /**
     * Redis payload와 DB READY 핵심 식별값(cacheKey/sourceRunId)이 일치하는지 확인합니다.
     * 파싱 실패 시 불일치로 간주하고 DB 기준으로 복구합니다.
     */
    private boolean matchesPayload(String rawPayload, AnalysisCache cache) {
        try {
            JsonNode node = objectMapper.readTree(rawPayload);
            String payloadCacheKey = text(node, "cacheKey");
            String payloadSourceRunId = text(node, "sourceRunId");
            return cache.getCacheKey().equals(payloadCacheKey)
                    && cache.getSourceRunId().equals(payloadSourceRunId);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * DB READY 레코드를 기준으로 Redis READY 키를 다시 기록합니다.
     */
    private void syncRedisReady(AnalysisCache cache) {
        try {
            String readyKey = redisKeyPolicy.readyKey(cache.getCacheKey());
            String payload = objectMapper.writeValueAsString(new ReadyPayload(
                    cache.getCacheKey(),
                    cache.getSourceRunId()
            ));
            redisStore.set(readyKey, payload, redisKeyPolicy.readyTtl());
        } catch (JsonProcessingException e) {
            log.warn("[CACHE] failed to serialize redis ready payload. cacheKey={}", abbreviate(cache.getCacheKey()), e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String abbreviate(String key) {
        if (key == null || key.isBlank()) {
            return "<empty>";
        }
        return key.length() <= 12 ? key : key.substring(0, 12);
    }

}
