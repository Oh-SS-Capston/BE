package com.example.ossdoc.global.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "github.stats")
public class GithubStatsProperties {
    // GitHub REST API 토큰
    // 비워두면 비인증 요청으로 동작하지만 rate limit낮음
    private String token = "";

    // 통계 스냅샷 캐시 유지 시간
    private long cacheTtlMinutes = 360;

    // GitHub API 요청 타임아웃
    private long apiTimeoutSeconds = 30;
}