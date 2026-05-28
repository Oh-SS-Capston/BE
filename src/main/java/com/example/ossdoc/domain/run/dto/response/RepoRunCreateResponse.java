package com.example.ossdoc.domain.run.dto.response;

import com.example.ossdoc.domain.run.enums.RunStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class RepoRunCreateResponse {
    /**
     * 이번 요청으로 반환되는 run 식별자입니다.
     * - cacheHit=true인 경우: 재사용된 run(또는 공유 복제 run)의 ID
     * - cacheHit=false인 경우: 신규 생성된 run ID
     */
    private String runId;

    /**
     * 반환 run의 현재 상태입니다.
     */
    private RunStatus status;

    /**
     * 요청 저장소의 확정 커밋 SHA입니다.
     */
    private String commitSha;

    /**
     * 반환 run의 워크스페이스 루트 경로입니다.
     */
    private String workspaceRoot;

    /**
     * 캐시 재사용 여부입니다.
     * - true: 정적분석/LLM 파이프라인을 건너뛰고 기존 결과를 재사용
     * - false: 신규 분석 경로를 진행
     */
    private boolean cacheHit;

    /**
     * 캐시 판정에 사용된 키입니다.
     * - hit 시: 실제 적중된 READY 캐시 키
     * - miss 시: 이번 요청 기준으로 생성된 캐시 키
     */
    private String cacheKey;

    /**
     * cacheHit=true일 때 원본 결과 run ID입니다.
     * - 같은 사용자 hit면 반환 runId와 같을 수 있습니다.
     * - 전역 캐시 공유 hit면 원본 runId를 담고, 반환 runId는 복제된 공유 run이 됩니다.
     */
    private String sourceRunId;

    /**
     * FE에서 "최근 분석 n시간 전" 표시를 계산할 때 사용하는 시각 정보입니다.
     */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
