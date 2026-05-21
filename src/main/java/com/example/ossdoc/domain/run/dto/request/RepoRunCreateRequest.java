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
}
