package com.example.ossdoc.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 로컬 Ollama 연동 설정.
 * yaml prefix: {@code ollama}
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ollama")
public class OllamaConfig {

    /**
     * Ollama 데몬 주소. 기본은 로컬 데몬.
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * 사용할 모델 태그. {@code ollama list}에 보이는 이름과 정확히 같아야 한다.
     */
    private String model = "qwen3.5:9b";

    /**
     * 입력 컨텍스트 크기.
     * Ollama 기본값이 2048이라 명시하지 않으면 프롬프트가 조용히 왼쪽부터 잘린다
     * (= 출력 스키마 지시문이 날아가 JSON 파싱이 계속 실패한다).
     */
    private int numCtx = 16384;

    /**
     * 출력 토큰 상한. 단계별 요청값과 min()으로 clamp된다.
     * CPU 환경에서는 이 값이 곧 응답 대기 시간이므로 보수적으로 잡는다.
     */
    private int numPredict = 6000;

    /**
     * 구조화 JSON 생성이라 낮게 둔다.
     */
    private double temperature = 0.2;

    /**
     * 읽기 타임아웃(분). CPU 추론은 한 호출이 수십 분까지 걸릴 수 있다.
     */
    private int timeoutMinutes = 30;

    /**
     * true면 Ollama의 JSON 강제 모드를 켠다.
     * 9B급 모델이 프롬프트 지시만으로 순수 JSON을 내놓게 하기 어려워 기본 ON.
     */
    private boolean jsonFormat = true;

    /**
     * qwen3 계열 hybrid reasoning의 추론 토큰 생성 여부.
     * 추론 토큰은 CPU 시간만 쓰고 산출물에 쓰이지 않으므로 기본 OFF.
     */
    private boolean think = false;

    /**
     * Ollama 호출용 RestClient.
     *
     * <p>기본 RestClient에는 읽기 타임아웃이 없어 CPU 추론 중 무한 대기가 될 수 있다.
     * 사용자 검증 코드와 동일하게 java.net.http 클라이언트에 커넥트 30초 /
     * 읽기 {@code timeoutMinutes}분을 명시한다.</p>
     */
    @Bean
    public RestClient ollamaRestClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .build()
        );
        requestFactory.setReadTimeout(Duration.ofMinutes(timeoutMinutes));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("content-type", "application/json")
                .build();
    }
}
