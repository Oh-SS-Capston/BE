package com.example.ossdoc.domain.run.support;

import lombok.Builder;
import lombok.Getter;

/**
 * 분석 결과 캐시 키를 만들 때 사용하는 입력값 묶음입니다.
 * <p>
 * 왜 필요한가:
 * 캐시 키 생성 규칙을 한 객체로 모아야 호출부에서 누락되는 값을 줄이고,
 * 키 정책이 바뀔 때 영향 범위를 한 곳으로 제한할 수 있습니다.
 */
@Getter
@Builder
public class RunAnalysisCacheKeySeed {

    /**
     * 사용자가 입력한 저장소 URL 원문입니다.
     */
    private final String repoUrl;

    /**
     * 분석 기준이 되는 커밋 SHA입니다.
     */
    private final String commitSha;

    /**
     * 파이프라인 계약 버전입니다.
     * 구조 분석 규칙이 바뀌면 값을 올려서 캐시를 분리합니다.
     */
    private final String pipelineContractVersion;

    /**
     * LLM 프로필 버전입니다.
     * 모델/파라미터 세트가 달라지면 값을 올려서 캐시를 분리합니다.
     */
    private final String llmProfileVersion;

    /**
     * 프롬프트 템플릿 버전입니다.
     * 템플릿이 바뀌면 같은 SHA여도 결과가 달라질 수 있으므로 분리합니다.
     */
    private final String promptTemplateVersion;

    /**
     * 출력 스키마 버전입니다.
     * JSON 계약이 달라질 때 하위 호환 문제를 피하기 위해 분리합니다.
     */
    private final String outputSchemaVersion;

    /**
     * 실행 옵션 시그니처입니다.
     * include/exclude, 모드 등의 실행 옵션 차이를 키에 반영합니다.
     */
    private final String runOptionsSignature;
}
