package com.example.ossdoc.domain.run.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RunErrorCode implements BaseCode {

    INVALID_REPO_URL(
            HttpStatus.BAD_REQUEST,
            "RUN400_1",
            "GitHub 레포지토리 URL 형식이 올바르지 않습니다."
    ),

    GITHUB_API_FAILED(
            HttpStatus.BAD_GATEWAY,
            "RUN502_1",
            "GitHub 레포지토리 정보를 조회하지 못했습니다."
    ),

    DOWNLOAD_FAILED(
            HttpStatus.BAD_GATEWAY,
            "RUN502_2",
            "레포지토리 ZIP 다운로드에 실패했습니다."
    ),

    UNZIP_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "RUN500_1",
            "레포지토리 압축 해제에 실패했습니다."
    ),

    CLONE_FAILED(
            HttpStatus.BAD_GATEWAY,
            "RUN502_3",
            "레포지토리 clone에 실패했습니다."
    ),

    JOB_MANIFEST_WRITE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "RUN500_2",
            "job_manifest.json 작성에 실패했습니다."
    ),

    RUN_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RUN404_1",
            "존재하지 않는 run 입니다."
    ),

    RUN_FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "RUN403_1",
            "해당 run 접근 권한이 없습니다."
    ),

    PIPELINE_JOB_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RUN404_2",
            "존재하지 않는 파이프라인 작업입니다."
    ),

    PIPELINE_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "RUN409_1",
            "이미 파이프라인 작업이 존재합니다."
    ),

    PIPELINE_EXECUTION_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "RUN500_3",
            "분석 파이프라인 실행에 실패했습니다."
    );

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
