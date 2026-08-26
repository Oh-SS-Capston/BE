package com.example.ossdoc.domain.run.cache.entity;

import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * repoUrl + commitSha + 버전축으로 계산된 캐시 키 단위의 메타 정보입니다.
 *
 * 왜 필요한가:
 * - Redis를 빠른 조회 계층으로 쓰더라도, 최종 정본은 DB에 남겨야 운영 중 복구/추적이 가능합니다.
 * - 캐시 hit/miss/실패 이력을 DB에 남기면, 캐시 품질과 장애 원인을 분석하기 쉬워집니다.
 */
@Entity
@Table(
        name = "analysis_cache",
        indexes = {
                @Index(name = "ix_analysis_cache_repo_sha", columnList = "repo_url_norm, commit_sha"),
                @Index(name = "ix_analysis_cache_status", columnList = "status")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AnalysisCache extends BaseAuditedEntity {

    /**
     * 캐시 식별자(PK)입니다.
     * SHA-256 해시 문자열(64자)을 저장합니다.
     */
    @Id
    @Column(name = "cache_key", nullable = false, length = 64)
    private String cacheKey;

    /**
     * 정규화된 저장소 URL입니다.
     * 예: github://apache/commons-cli
     */
    @Column(name = "repo_url_norm", nullable = false, length = 300)
    private String repoUrlNorm;

    /**
     * 분석 기준 커밋 SHA입니다.
     */
    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    /**
     * 캐시 상태입니다.
     * READY/IN_PROGRESS/FAILED 중 하나를 가집니다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisCacheStatus status;

    /**
     * READY 결과를 만들어낸 원본 run_id입니다.
     * 캐시 히트 시 어떤 run 결과를 재사용하는지 역추적할 수 있습니다.
     */
    @Column(name = "source_run_id", length = 80)
    private String sourceRunId;

    /**
     * 이 캐시 번들을 만든 LLM 제공자 이름입니다(OLLAMA/CLAUDE).
     *
     * 왜 캐시 행에 두는가:
     * - DB 폴백 조회는 cacheKey가 아니라 repo/commit으로 찾습니다. 그래서 키에 provider를
     *   넣는 것만으로는 "claude 요청에 ollama 결과가 나가는" 재사용을 막지 못합니다.
     *   조회 조건으로 쓰려면 repoUrlNorm/commitSha와 같은 층위로 비정규화해 두어야 합니다.
     *
     * nullable인 이유:
     * - 이 컬럼 이전에 쌓인 캐시 행이 있습니다. 조회 시 설정 기본 제공자로 간주해 계속 재사용합니다.
     */
    @Column(name = "llm_provider", length = 20)
    private String llmProvider;

    /**
     * 결과 산출물 포인터 묶음(JSON)입니다.
     * 예: artifact kind별 artifactId/path/url 메타.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifact_bundle_json", columnDefinition = "jsonb")
    private JsonNode artifactBundleJson;

    /**
     * 결과 품질 시그니처(옵션)입니다.
     * quality report 요약 해시 등을 저장해 재사용 품질 검증에 활용합니다.
     */
    @Column(name = "quality_hash", length = 128)
    private String qualityHash;

    /**
     * 캐시 만료 시각입니다.
     * null이면 만료 정책을 외부(TTL/운영정책)에서 관리합니다.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * 캐시 히트 횟수입니다.
     */
    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    /**
     * 마지막 히트 시각입니다.
     */
    @Column(name = "last_hit_at")
    private LocalDateTime lastHitAt;

    public AnalysisCache(String cacheKey, String repoUrlNorm, String commitSha) {
        this.cacheKey = cacheKey;
        this.repoUrlNorm = repoUrlNorm;
        this.commitSha = commitSha;
        this.status = AnalysisCacheStatus.IN_PROGRESS;
        this.hitCount = 0L;
    }

    /**
     * 이 캐시 행이 어느 제공자의 산출물인지 기록합니다.
     * markReady/markFailed와 무관하게 upsert 시점에 항상 최신 값으로 맞춥니다.
     */
    public void assignLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    /**
     * 파이프라인 시작 직후, 동일 키 분석이 진행 중임을 표시합니다.
     */
    public void markInProgress(LocalDateTime expiresAt) {
        this.status = AnalysisCacheStatus.IN_PROGRESS;
        this.expiresAt = expiresAt;
        this.sourceRunId = null;
        this.artifactBundleJson = null;
        this.qualityHash = null;
    }

    /**
     * 필수 산출물이 모두 준비된 경우 READY로 전환합니다.
     */
    public void markReady(
            String sourceRunId,
            JsonNode artifactBundleJson,
            String qualityHash,
            LocalDateTime expiresAt
    ) {
        this.status = AnalysisCacheStatus.READY;
        this.sourceRunId = sourceRunId;
        this.artifactBundleJson = artifactBundleJson;
        this.qualityHash = qualityHash;
        this.expiresAt = expiresAt;
    }

    /**
     * 분석 실패 시 FAILED로 전환합니다.
     *
     * W10 정책:
     * - sourceRunId를 남겨 두어 "어떤 실패 실행을 근거로 쿨다운을 거는지" 추적합니다.
     * - expiresAt은 FAILED 쿨다운 만료 시각(retryAfter)로 사용합니다.
     */
    public void markFailed(String sourceRunId, LocalDateTime expiresAt) {
        this.status = AnalysisCacheStatus.FAILED;
        this.expiresAt = expiresAt;
        this.sourceRunId = sourceRunId;
        this.artifactBundleJson = null;
        this.qualityHash = null;
    }

    /**
     * 캐시 히트가 발생한 시점을 기록합니다.
     */
    public void recordHit(LocalDateTime hitAt) {
        this.hitCount += 1;
        this.lastHitAt = hitAt;
    }
}
