package com.example.ossdoc.domain.run.cache.service;

import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheFailedCooldownResult;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.repository.AnalysisCacheRepository;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisKeyPolicy;
import com.example.ossdoc.domain.run.cache.support.AnalysisCacheRedisStore;
import com.example.ossdoc.domain.run.cache.support.ReadyPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ossdoc.global.llm.service.support.LlmChatClientResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * W05: Redis -> DB ?쒖쑝濡?READY 罹먯떆瑜?議고쉶?섎뒗 ?쒕퉬?ㅼ엯?덈떎.
 *
 * 議고쉶 ?뺤콉:
 * 1) Redis READY ?ㅻ? 癒쇱? 議고쉶
 * 2) Redis hit?щ룄 DB READY ?덉퐫?쒕줈 理쒖냼 臾닿껐??寃利? * 3) 遺덉씪移섎㈃ Redis ?뺣━ ??DB ?ъ“?? * 4) DB?먯꽌 READY瑜?李얠쑝硫?Redis瑜??щ룞湲고솕?섍퀬 hit 泥섎━
 * 5) ?앷퉴吏 ?놁쑝硫?miss 諛섑솚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisCacheLookupService {

    private final AnalysisCacheRepository analysisCacheRepository;
    private final AnalysisCacheRedisKeyPolicy redisKeyPolicy;
    private final AnalysisCacheRedisStore redisStore;
    private final ObjectMapper objectMapper;
    private final LlmChatClientResolver llmChatClientResolver;

    @Transactional
    public AnalysisCacheLookupResult lookupReady(
            String cacheKey,
            String repoUrlNorm,
            String commitSha,
            String llmProvider
    ) {
        String readyKey = redisKeyPolicy.readyKey(cacheKey);

        Optional<String> redisPayload = redisStore.get(readyKey);
        if (redisPayload.isPresent()) {
            Optional<AnalysisCache> byKeyReady = analysisCacheRepository.findByKeyAndStatus(
                    cacheKey,
                    AnalysisCacheStatus.READY
            );
            if (byKeyReady.isPresent() && isUsableReady(byKeyReady.get())) {
                AnalysisCache cache = byKeyReady.get();
                // Redis payload? DB ?덉퐫?쒓? ?ㅻⅤ硫?利됱떆 ?щ룞湲고솕???ㅼ쓬 議고쉶遺???덉젙?뷀빀?덈떎.
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

            // Redis??hit?몃뜲 DB READY媛 ?놁쑝硫?stale ?ㅼ씠誘濡??뺣━?섍퀬 DB ?ъ“?뚮줈 ?대갚?⑸땲??
            redisStore.delete(readyKey);
            log.info("[CACHE] stale redis ready key removed. key={}", abbreviate(cacheKey));
        }

        /*
         * DB 폴백은 cacheKey가 아니라 repo/commit으로 찾기 때문에 키의 provider 축이 여기서는 안 먹습니다.
         * (실측: reason=DB_HIT_CACHE_KEY_MISMATCH_SYNCED로 키가 다른 행이 그대로 수용됐습니다.)
         * 그래서 provider를 조회 조건으로 직접 넣어 다른 제공자의 산출물이 새어 나가지 않게 합니다.
         *
         * 나머지 버전 축(prompt/schema/options)은 종전대로 이 경로에서 우회됩니다.
         * 그것들은 라벨이 바뀌어도 산출물이 같을 수 있는 반면, provider는 내용 자체가 달라집니다.
         */
        String effectiveProvider = llmProvider == null || llmProvider.isBlank()
                ? llmChatClientResolver.defaultProvider().name()
                : llmProvider;

        Optional<AnalysisCache> dbReady = analysisCacheRepository
                .findLatestByRepoAndCommitAndProviderAndStatus(
                        repoUrlNorm,
                        commitSha,
                        AnalysisCacheStatus.READY,
                        effectiveProvider,
                        llmChatClientResolver.defaultProvider().name()
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

    /**
     * W10: FAILED 罹먯떆 荑⑤떎???곹깭瑜?議고쉶?⑸땲??
     *
     * 議고쉶 ?뺤콉:
     * 1) ?숈씪 cacheKey FAILED ?곗꽑 ?뺤씤
     * 2) ?놁쑝硫??숈씪 repo+sha 湲곗? 理쒖떊 FAILED ?뺤씤
     * 3) expiresAt(retryAfter) ?댁쟾?대㈃ 荑⑤떎???쒖꽦?쇰줈 ?먮떒
     */
    @Transactional(readOnly = true)
    public AnalysisCacheFailedCooldownResult lookupFailedCooldown(
            String cacheKey,
            String repoUrlNorm,
            String commitSha
    ) {
        LocalDateTime now = LocalDateTime.now();

        Optional<AnalysisCache> failedByKey = analysisCacheRepository.findByKeyAndStatus(
                cacheKey,
                AnalysisCacheStatus.FAILED
        );
        if (failedByKey.isPresent() && isCoolingDownFailed(failedByKey.get(), now)) {
            AnalysisCache cache = failedByKey.get();
            return AnalysisCacheFailedCooldownResult.active(
                    cache.getCacheKey(),
                    cache.getSourceRunId(),
                    cache.getExpiresAt(),
                    "FAILED_COOLDOWN_BY_KEY"
            );
        }

        Optional<AnalysisCache> failedByRepoSha = analysisCacheRepository
                .findLatestByRepoAndCommitAndStatus(
                        repoUrlNorm,
                        commitSha,
                        AnalysisCacheStatus.FAILED
                );
        if (failedByRepoSha.isPresent() && isCoolingDownFailed(failedByRepoSha.get(), now)) {
            AnalysisCache cache = failedByRepoSha.get();
            return AnalysisCacheFailedCooldownResult.active(
                    cache.getCacheKey(),
                    cache.getSourceRunId(),
                    cache.getExpiresAt(),
                    cache.getCacheKey().equals(cacheKey)
                            ? "FAILED_COOLDOWN_DB_BY_KEY"
                            : "FAILED_COOLDOWN_DB_BY_REPO_SHA"
            );
        }

        return AnalysisCacheFailedCooldownResult.inactive("FAILED_COOLDOWN_NOT_ACTIVE");
    }

    private boolean isUsableReady(AnalysisCache cache) {
        return cache.getStatus() == AnalysisCacheStatus.READY
                && cache.getSourceRunId() != null
                && !cache.getSourceRunId().isBlank()
                && cache.getArtifactBundleJson() != null;
    }

    /**
     * FAILED 罹먯떆媛 ?꾩쭅 荑⑤떎??援ш컙?몄? ?먯젙?⑸땲??
     * expiresAt??鍮꾩뼱 ?덉쑝硫?荑⑤떎???뺤콉???곸슜?섏? ?딆뒿?덈떎.
     */
    private boolean isCoolingDownFailed(AnalysisCache cache, LocalDateTime now) {
        if (cache.getStatus() != AnalysisCacheStatus.FAILED) {
            return false;
        }
        if (cache.getExpiresAt() == null) {
            return false;
        }
        return now.isBefore(cache.getExpiresAt());
    }

    /**
     * Redis payload? DB READY ?듭떖 ?앸퀎媛?cacheKey/sourceRunId)???쇱튂?섎뒗吏 ?뺤씤?⑸땲??
     * ?뚯떛 ?ㅽ뙣 ??遺덉씪移섎줈 媛꾩＜?섍퀬 DB 湲곗??쇰줈 蹂듦뎄?⑸땲??
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
     * DB READY ?덉퐫?쒕? 湲곗??쇰줈 Redis READY ?ㅻ? ?ㅼ떆 湲곕줉?⑸땲??
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

