package com.example.ossdoc.domain.run.cache.repository;

import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 분석 캐시 메타 조회/갱신용 리포지토리입니다.
 *
 * 현재 단계에서는 스키마/조회 키를 고정하기 위해 최소 쿼리만 노출합니다.
 * 실제 캐시 조회 서비스(CacheLookupService)는 다음 단위(W05)에서 연결합니다.
 */
public interface AnalysisCacheRepository extends JpaRepository<AnalysisCache, String> {

    Optional<AnalysisCache> findByCacheKey(String cacheKey);

    Optional<AnalysisCache> findByRepoUrlNormAndCommitShaAndStatus(
            String repoUrlNorm,
            String commitSha,
            AnalysisCacheStatus status
    );
}
