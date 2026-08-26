package com.example.ossdoc.domain.run.cache.enums;

/**
 * 분석 캐시 상태를 나타냅니다.
 *
 * READY:
 * - 재사용 가능한 완성 결과가 저장된 상태
 *
 * IN_PROGRESS:
 * - 동일 키에 대한 분석이 현재 진행 중인 상태
 *
 * FAILED:
 * - 최근 시도에서 실패해 즉시 재사용하면 안 되는 상태
 */
public enum AnalysisCacheStatus {
    READY,
    IN_PROGRESS,
    FAILED
}
