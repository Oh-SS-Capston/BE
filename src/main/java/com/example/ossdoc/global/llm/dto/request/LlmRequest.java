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
     * - null이면 서버가 runId 기준으로 자동 조립한다.
     * - 값이 있으면 외부에서 조합한 컨텍스트를 그대로 사용할 수 있다.
     */
    private Map<String, Object> structureEngineOutput;

    /**
     * LLM 근거 스니펫 묶음.
     * - null/빈 배열이면 서버가 rule/class-map 기반 근거를 자동 구성한다.
     */
    private List<EvidenceSnippet> evidenceBundle;

    /**
     * true(기본): 서버가 runId 기준 자동 컨텍스트를 우선 생성한다.
     * false: 요청 바디에 전달된 structure/evidence를 그대로 사용한다.
     */
    private Boolean autoAssemble;

    /**
     * true(기본): LLM 자연어 응답을 한국어로 생성한다.
     */
    private Boolean preferKorean;

    /**
     * 사용할 LLM 제공자. {@code "ollama"} 또는 {@code "claude"}.
     *
     * <p>null/빈 값이면 run에 기록된 제공자, 그것도 없으면 {@code ossdoc.llm.provider} 설정값을 쓴다.
     * 문자열로 받는 이유는 HTTP 요청에서 대소문자를 가리지 않고 받기 위해서다.
     * 인식할 수 없는 값은 기본값으로 흘리지 않고 실패로 처리한다.</p>
     */
    private String provider;

    public boolean useAutoAssemble() {
        return autoAssemble == null || autoAssemble;
    }

    public boolean useKorean() {
        return preferKorean == null || preferKorean;
    }

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
