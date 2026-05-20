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
}
