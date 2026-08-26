package com.example.ossdoc.domain.run.cache.repository;

import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 폴백 조회가 제공자를 가리는지 검증한다.
 *
 * <p>이 조회는 cacheKey가 아니라 repo/commit으로 찾기 때문에, 캐시 키에 provider 축을 넣는 것만으로는
 * 다른 제공자의 산출물이 재사용되는 것을 막지 못했다. 실제로 claude 요청에 ollama 결과가 나갈 수 있었고
 * 로그에는 {@code DB_HIT_CACHE_KEY_MISMATCH_SYNCED}로만 남았다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisCacheProviderScopedLookupTest {

    private static final String REPO = "github://apache/provider-scope-test";
    private static final String SHA = "abc1234567890abc1234567890abc1234567890a";

    @Autowired
    private AnalysisCacheRepository analysisCacheRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("ollama가 만든 READY 캐시는 claude 요청에 재사용되지 않는다")
    void doesNotReuseOtherProviderResult() {
        persistReady("cache-ollama", "OLLAMA");

        Optional<AnalysisCache> forClaude = analysisCacheRepository
                .findLatestByRepoAndCommitAndProviderAndStatus(
                        REPO, SHA, AnalysisCacheStatus.READY, "CLAUDE", "OLLAMA");

        Optional<AnalysisCache> forOllama = analysisCacheRepository
                .findLatestByRepoAndCommitAndProviderAndStatus(
                        REPO, SHA, AnalysisCacheStatus.READY, "OLLAMA", "OLLAMA");

        assertThat(forClaude).isEmpty();
        assertThat(forOllama).isPresent();
    }

    @Test
    @DisplayName("provider가 기록되기 전의 캐시는 기본 제공자 산출물로 간주해 계속 재사용한다")
    void treatsLegacyRowAsDefaultProvider() {
        // llm_provider 컬럼이 생기기 전에 쌓인 행이다. 죽은 캐시로 만들면 전면 재분석이 걸린다.
        persistReady("cache-legacy", null);

        Optional<AnalysisCache> forDefault = analysisCacheRepository
                .findLatestByRepoAndCommitAndProviderAndStatus(
                        REPO, SHA, AnalysisCacheStatus.READY, "OLLAMA", "OLLAMA");

        Optional<AnalysisCache> forOther = analysisCacheRepository
                .findLatestByRepoAndCommitAndProviderAndStatus(
                        REPO, SHA, AnalysisCacheStatus.READY, "CLAUDE", "OLLAMA");

        assertThat(forDefault).isPresent();
        assertThat(forOther).isEmpty();
    }

    private void persistReady(String cacheKey, String provider) {
        AnalysisCache cache = new AnalysisCache(cacheKey, REPO, SHA);
        cache.assignLlmProvider(provider);
        cache.markReady(
                "run_" + cacheKey,
                JsonNodeFactory.instance.objectNode(),
                "quality-hash",
                LocalDateTime.now().plusHours(1)
        );

        entityManager.persist(cache);
        entityManager.flush();
        entityManager.clear();
    }
}
