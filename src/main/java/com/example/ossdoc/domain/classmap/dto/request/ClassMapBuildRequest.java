// 역할: Public API 중심 클래스 맵 생성 요청 파라미터를 받는다.
package com.example.ossdoc.domain.classmap.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ClassMapBuildRequest {
    @NotBlank
    private String runId;

    @Min(10)
    private Integer maxNodes = 120;

    @Min(20)
    private Integer maxEdges = 240;

    @Min(1)
    private Integer startHereTopN = 8;

    public int safeMaxNodes() {
        int raw = maxNodes == null ? 120 : maxNodes;
        return Math.max(10, Math.min(400, raw));
    }

    public int safeMaxEdges() {
        int raw = maxEdges == null ? 240 : maxEdges;
        return Math.max(20, Math.min(1000, raw));
    }

    public int safeStartHereTopN() {
        int raw = startHereTopN == null ? 8 : startHereTopN;
        return Math.max(1, Math.min(30, raw));
    }
}
