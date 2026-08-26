package com.example.ossdoc.global.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 산출물 저장 방식 토글.
 * yaml prefix: {@code ossdoc.llm.output}
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.llm.output")
public class LlmOutputProperties {

    /**
     * true이면 LLM 산출물을 로컬 파일로만 저장하고 S3 업로드와 DB 저장을 건너뛴다.
     *
     * <p>모델 교체 실험 중 결과물만 눈으로 확인하려는 용도다. 켜져 있는 동안에는
     * artifact 테이블에 LLM 산출물 5종이 남지 않으므로
     * READY 캐시 발행과 산출물 조회 API는 동작하지 않는다.
     * llm_scenario_cache 저장도 함께 생략된다.</p>
     *
     * <p>이 토글은 LLM 경로에만 적용된다. 다른 파이프라인 단계의 저장 흐름은 건드리지 않는다.</p>
     */
    private boolean localOnly = false;
}
