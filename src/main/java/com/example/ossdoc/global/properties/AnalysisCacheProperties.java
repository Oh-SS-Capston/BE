package com.example.ossdoc.global.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 분석 캐시 키를 구성하는 버전 축 설정입니다.
 *
 * 왜 필요한가:
 * - 로직/프롬프트/스키마 변경 시 한 곳의 값만 올려서 캐시를 안전하게 분리하기 위함입니다.
 * - 서비스 코드에 하드코딩된 문자열을 없애 운영 중 버전 관리 실수를 줄입니다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.analysis-cache")
public class AnalysisCacheProperties {

    /**
     * 구조 분석 파이프라인 계약 버전입니다.
     * 분석 의미가 바뀌면 이 값을 올립니다.
     */
    private String pipelineContractVersion = "pipeline-v1";

    /**
     * LLM 프로필 버전입니다.
     * 모델/파라미터 정책이 바뀌면 이 값을 올립니다.
     */
    private String llmProfileVersion = "llm-profile-v1";

    /**
     * 프롬프트 템플릿 버전입니다.
     * 프롬프트 문구/구조 변경 시 이 값을 올립니다.
     */
    private String promptTemplateVersion = "prompt-v1";

    /**
     * 출력 JSON 스키마 버전입니다.
     * 계약 필드가 바뀌면 이 값을 올립니다.
     */
    private String outputSchemaVersion = "schema-v1";

    /**
     * 실행 옵션 시그니처 기본값입니다.
     * 현재는 고정값을 사용하고, 추후 요청 옵션 확장 시 동적으로 구성합니다.
     */
    private String defaultRunOptionsSignature = "options-default-v1";

    /**
     * Redis 기반 캐시를 활성화할지 여부입니다.
     * false면 Noop 저장소를 사용하고, true면 실제 Redis 저장소를 사용합니다.
     */
    private boolean redisEnabled = false;

    /**
     * Redis READY 키 prefix입니다.
     * 예: analysis:ready
     */
    private String redisReadyKeyPrefix = "analysis:ready";

    /**
     * Redis LOCK 키 prefix입니다.
     * 예: analysis:lock
     */
    private String redisLockKeyPrefix = "analysis:lock";

    /**
     * READY 키 TTL(시간)입니다.
     * 기본 24시간으로 두고 운영 환경에서 조정합니다.
     */
    private long redisReadyTtlHours = 24;

    /**
     * LOCK 키 TTL(분)입니다.
     * 기본 20분(권장 범위 10~30분)으로 두고 운영 환경에서 조정합니다.
     */
    private long redisLockTtlMinutes = 20;

    /**
     * FAILED 캐시 쿨다운(초)입니다.
     *
     * W10 완화 정책:
     * - 사용자 체감을 해치지 않도록 짧은 30초 디바운스만 둡니다.
     * - 동일 key가 직전 FAILED일 때 아주 짧은 폭주만 막고 빠르게 재시도를 허용합니다.
     */
    private long failedCooldownSeconds = 30;
}
