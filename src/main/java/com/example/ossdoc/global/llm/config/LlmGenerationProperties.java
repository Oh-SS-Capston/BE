package com.example.ossdoc.global.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 생성 단계 튜닝 값.
 * yaml prefix: {@code ossdoc.llm.generation}
 *
 * <p>기본값은 기존 LlmService 하드코딩 값과 동일하다.
 * 로컬 CPU 모델처럼 생성 속도가 느린 환경에서는 yaml에서 낮춰 잡는다.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.llm.generation")
public class LlmGenerationProperties {

    /**
     * step ①에서 생성할 주의사항 최대 개수.
     */
    private int maxCautions = 12;

    /**
     * step ②에서 생성할 시나리오 최대 개수.
     */
    private int maxScenarios = 4;

    /**
     * 시나리오 하나당 단계 최대 개수.
     */
    private int maxStepsPerScenario = 8;

    /**
     * step ① 출력 토큰 상한. 제공자 설정 상한과 min()으로 clamp된다.
     */
    private int tokensCautions = 16000;

    /**
     * step ② 출력 토큰 상한. 제공자 설정 상한과 min()으로 clamp된다.
     */
    private int tokensScenarios = 20000;
}
