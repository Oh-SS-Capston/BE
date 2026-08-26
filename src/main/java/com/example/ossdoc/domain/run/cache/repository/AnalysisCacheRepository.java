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

    /*
     * provider를 가리지 않는 조회입니다. FAILED 쿨다운 판정에만 씁니다.
     * 쿨다운은 "같은 repo/commit이 방금 실패했으니 잠시 쉬자"는 보호 장치이고,
     * 실패는 대개 구조 단계에서 나므로 제공자와 무관합니다.
     * READY 재사용은 산출물 내용이 걸린 문제라 provider를 가리는 아래 조회를 씁니다.
     */
    default Optional<AnalysisCache> findLatestByRepoAndCommitAndStatus(
            String repoUrlNorm,
            String commitSha,
            AnalysisCacheStatus status
    ) {
        return findLatestByRepoCommitStatus(repoUrlNorm, commitSha, status, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    /*
     * DB 폴백 조회입니다. cacheKey가 아니라 repo/commit으로 찾기 때문에,
     * 캐시 키에 넣은 provider 축이 이 경로에서는 적용되지 않습니다.
     * 그래서 provider를 조회 조건으로 함께 받습니다.
     *
     * COALESCE를 쓰는 이유:
     * - llm_provider 컬럼 이전에 쌓인 행은 값이 null입니다. 그 행들을 죽은 캐시로 만들지 않고
     *   설정 기본 제공자의 산출물로 간주해 계속 재사용합니다.
     *   (이 컬럼이 생기기 전까지 기본값 외의 제공자로 만들어진 캐시는 존재하지 않습니다.)
     */
    @Query("""
            SELECT c
            FROM AnalysisCache c
            WHERE c.repoUrlNorm = :repoUrlNorm
              AND c.commitSha = :commitSha
              AND c.status = :status
              AND COALESCE(c.llmProvider, :defaultLlmProvider) = :llmProvider
            ORDER BY c.updatedAt DESC
            """)
    List<AnalysisCache> findLatestByRepoCommitProviderStatus(
            @Param("repoUrlNorm") String repoUrlNorm,
            @Param("commitSha") String commitSha,
            @Param("status") AnalysisCacheStatus status,
            @Param("llmProvider") String llmProvider,
            @Param("defaultLlmProvider") String defaultLlmProvider,
            Pageable pageable
    );

    default Optional<AnalysisCache> findLatestByRepoAndCommitAndProviderAndStatus(
            String repoUrlNorm,
            String commitSha,
            AnalysisCacheStatus status,
            String llmProvider,
            String defaultLlmProvider
    ) {
        return findLatestByRepoCommitProviderStatus(
                repoUrlNorm,
                commitSha,
                status,
                llmProvider,
                defaultLlmProvider,
                PageRequest.of(0, 1)
        ).stream().findFirst();
    }
}
