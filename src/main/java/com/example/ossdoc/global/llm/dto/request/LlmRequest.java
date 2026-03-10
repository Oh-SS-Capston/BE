package com.example.ossdoc.global.llm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    @NotNull
    private String runId;

    /**
     * 구조 엔진 분석 출력물 JSON.
     * Jackson 3.x(Spring Boot 4.x)에서 JsonNode 직접 역직렬화 불가 → Map 사용
     */
    @NotNull
    private Map<String, Object> structureEngineOutput;

    @NotNull
    private List<EvidenceSnippet> evidenceBundle;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceSnippet {
        private Long evidenceId;
        private String filePath;
        private Integer startLine;
        private Integer endLine;
        private String snippet;
        private String evidenceType;
    }
}