package com.example.ossdoc.domain.extraction.exception;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ExtractionErrorCode implements BaseCode {

    EXTRACTION_FAIL_FAST_ABORTED(HttpStatus.INTERNAL_SERVER_ERROR, "EXTRACTION_500_002", "failFast 설정으로 추출이 중단되었습니다."),
    EXTRACTION_ORCHESTRATION_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "EXTRACTION_500_003", "추출 병렬 처리 중 인터럽트가 발생했습니다."),
    EXTRACTION_ORCHESTRATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "EXTRACTION_500_004", "추출 병렬 처리 중 예기치 못한 오류가 발생했습니다."),

    EXTRACTION_PREFLIGHT_FAILED(HttpStatus.BAD_REQUEST, "EXTRACTION_400_003", "추출 사전 점검에 실패했습니다."),

    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "EXTRACTION_404_001", "워크스페이스를 찾을 수 없습니다."),
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "EXTRACTION_404_002", "Run을 찾을 수 없습니다.");

    private final HttpStatus status;
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
                .httpStatus(status)
                .build();
    }
}