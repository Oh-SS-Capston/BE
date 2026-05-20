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
     * 이후 짧은 쿨다운 정책과 함께 재시도를 제어할 수 있습니다.
     */
    public void markFailed(LocalDateTime expiresAt) {
        this.status = AnalysisCacheStatus.FAILED;
        this.expiresAt = expiresAt;
        this.sourceRunId = null;
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
