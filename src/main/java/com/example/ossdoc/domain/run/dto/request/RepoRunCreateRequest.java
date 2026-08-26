// domain/run/dto/RepoRunCreateRequest.java
package com.example.ossdoc.domain.run.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class RepoRunCreateRequest {
    @NotBlank
    private String repoUrl;

    // branch/tag/sha (optional)
    private String ref;

    /**
     * W11: 강제 재분석 플래그입니다.
     * true면 READY/FAILED 캐시를 읽지 않고 신규 분석을 수행합니다.
     */
    private boolean forceRebuild;

    /**
     * 이 run의 LLM 단계가 쓸 제공자입니다. {@code "ollama"} 또는 {@code "claude"}.
     *
     * 선택 필드라 보내지 않으면 서버 설정(ossdoc.llm.provider)이 쓰입니다.
     * 기존 클라이언트는 그대로 동작합니다.
     */
    private String llmProvider;
}
