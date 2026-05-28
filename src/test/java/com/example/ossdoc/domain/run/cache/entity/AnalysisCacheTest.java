package com.example.ossdoc.domain.run.cache.entity;

import com.example.ossdoc.domain.run.cache.enums.AnalysisCacheStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCacheTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void READY_전환시_결과_포인터와_품질해시가_저장된다() {
        AnalysisCache cache = new AnalysisCache(
                "cache-key-1",
                "github://apache/commons-cli",
                "e717fd63"
        );

        LocalDateTime expiresAt = LocalDateTime.now().plusDays(1);
        cache.markReady(
                "run_20260520_abc12345",
                objectMapper.createObjectNode().put("api_map", 101L),
                "quality-hash-v1",
                expiresAt
        );

        assertThat(cache.getStatus()).isEqualTo(AnalysisCacheStatus.READY);
        assertThat(cache.getSourceRunId()).isEqualTo("run_20260520_abc12345");
        assertThat(cache.getQualityHash()).isEqualTo("quality-hash-v1");
        assertThat(cache.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(cache.getArtifactBundleJson()).isNotNull();
    }

    @Test
    void hit_기록시_횟수와_마지막_시각이_갱신된다() {
        AnalysisCache cache = new AnalysisCache(
                "cache-key-2",
                "github://apache/commons-cli",
                "e717fd63"
        );

        LocalDateTime firstHit = LocalDateTime.now();
        LocalDateTime secondHit = firstHit.plusMinutes(1);

        cache.recordHit(firstHit);
        cache.recordHit(secondHit);

        assertThat(cache.getHitCount()).isEqualTo(2L);
        assertThat(cache.getLastHitAt()).isEqualTo(secondHit);
    }

    @Test
    void FAILED_전환시_READY_결과는_초기화된다() {
        AnalysisCache cache = new AnalysisCache(
                "cache-key-3",
                "github://apache/commons-cli",
                "e717fd63"
        );

        cache.markReady(
                "run_20260520_ready",
                objectMapper.createObjectNode().put("rule_candidates", 12L),
                "quality-hash-ready",
                LocalDateTime.now().plusHours(2)
        );

        LocalDateTime failedExpiresAt = LocalDateTime.now().plusMinutes(30);
        cache.markFailed("run_20260520_failed", failedExpiresAt);

        assertThat(cache.getStatus()).isEqualTo(AnalysisCacheStatus.FAILED);
        assertThat(cache.getSourceRunId()).isEqualTo("run_20260520_failed");
        assertThat(cache.getArtifactBundleJson()).isNull();
        assertThat(cache.getQualityHash()).isNull();
        assertThat(cache.getExpiresAt()).isEqualTo(failedExpiresAt);
    }
}
