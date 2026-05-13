package com.example.ossdoc.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "claude")
public class LlmConfig {

    /**
     * Anthropic API Key
     * 환경변수 CLAUDE_API_KEY 로 주입
     * 예: sk-ant-api03-...
     */
    private String apiKey;

    /**
     * 사용할 Claude 모델 ID
     * 예: claude-sonnet-4-5
     */
    private String model = "claude-sonnet-4-5";

    /**
     * Step2~5(시나리오/요약/API/파일트리) 기본 모델.
     * 비용과 속도를 위해 Haiku를 기본으로 사용하고,
     * 필요 시 서비스 레이어에서 Sonnet으로 폴백한다.
     */
    private String haikuModel = "claude-haiku-4-5";

    /**
     * 응답 최대 토큰 수
     */
    private int maxTokens = 20000;

    /**
     * Claude API 호출용 RestClient Bean.
     * spring-webflux 없이 spring-boot-starter-web 의 RestClient 사용.
     */
    @Bean
    public RestClient claudeRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
    }
}
