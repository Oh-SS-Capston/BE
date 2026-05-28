package com.example.ossdoc.domain.run.cache.repository;

import com.example.ossdoc.domain.run.cache.entity.AnalysisCache;
import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 분석 캐시 메타 조회/갱신 리포지토리입니다.
 */
public interface AnalysisCacheRepository extends JpaRepository<AnalysisCache, String> {

    @Query("""
            SELECT c
            FROM AnalysisCache c
            WHERE c.cacheKey = :cacheKey
              AND c.status = :status
            """)
    Optional<AnalysisCache> findByKeyAndStatus(
            @Param("cacheKey") String cacheKey,
            @Param("status") AnalysisCacheStatus status
    );

    @Query("""
            SELECT c
            FROM AnalysisCache c
            WHERE c.repoUrlNorm = :repoUrlNorm
              AND c.commitSha = :commitSha
              AND c.status = :status
            ORDER BY c.updatedAt DESC
            """)
    List<AnalysisCache> findLatestByRepoCommitStatus(
            @Param("repoUrlNorm") String repoUrlNorm,
            @Param("commitSha") String commitSha,
            @Param("status") AnalysisCacheStatus status,
            Pageable pageable
    );

    default Optional<AnalysisCache> findLatestByRepoAndCommitAndStatus(
            String repoUrlNorm,
            String commitSha,
            AnalysisCacheStatus status
    ) {
        return findLatestByRepoCommitStatus(repoUrlNorm, commitSha, status, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }
}
