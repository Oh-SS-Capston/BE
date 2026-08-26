package com.example.ossdoc.global.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 입력 보강 토글.
 * yaml prefix: {@code ossdoc.llm.input}
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.llm.input")
public class LlmInputProperties {

    private SuperCluster superCluster = new SuperCluster();
    private ApiFlow apiFlow = new ApiFlow();

    @Getter
    @Setter
    public static class SuperCluster {
        /**
         * true이면 step ③ 요약 단위를 super-cluster로 전환하고 step ⑤에 module-grain 라벨을 주입한다.
         * SUPER_SUBSYSTEMS_JSON artifact가 없으면 자동으로 OFF로 degrade된다.
         */
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class ApiFlow {
        /**
         * true이면 step ②/④에 API_FLOW_TRACE_JSON 입력을 추가한다.
         * API_FLOW_TRACE_JSON artifact가 없으면 자동으로 OFF로 degrade된다.
         */
        private boolean enabled = false;
    }
}
