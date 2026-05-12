package com.example.ossdoc.global.llm.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LlmErrorCode implements BaseCode {

    // Run 조회 실패
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "LLM_404_001", "RepoRun not found."),

    // Claude API 호출 관련
    CLAUDE_API_ERROR(HttpStatus.BAD_GATEWAY, "LLM_502_001", "Claude API returned an error response."),
    CLAUDE_API_CALL_FAILED(HttpStatus.BAD_GATEWAY, "LLM_502_002", "Failed to call Claude API."),

    // 요청 직렬화 실패
    REQUEST_SERIALIZE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "LLM_500_001", "Failed to build Claude request body."),

    // 응답 파싱 실패
    RESPONSE_PARSE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "LLM_500_002", "LLM response is not valid JSON."),

    // Artifact 저장 실패
    ARTIFACT_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "LLM_500_003", "Failed to save LLM artifact."),

    // 자동 컨텍스트 조립 실패
    CONTEXT_ASSEMBLE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "LLM_500_004", "Failed to assemble LLM context."),

    // 필수 산출물 누락
    REQUIRED_ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND, "LLM_404_002", "Required analysis artifact not found."),

    // 시나리오 캐시 저장 실패
    SCENARIO_CACHE_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "LLM_500_005", "Failed to save scenario cache.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public ReasonDTO getReason() {
        return ReasonDTO.builder()
                .message(message)
                .code(code)
                .isSuccess(false)
                .build();
    }

    @Override
    public ReasonDTO getReasonHttpStatus() {
        return ReasonDTO.builder()
                .message(message)
                .code(code)
                .isSuccess(false)
                .httpStatus(httpStatus)
                .build();
    }
}
