package com.example.ossdoc.domain.artifact.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ArtifactErrorCode implements BaseCode {

    ARTIFACT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "ARTIFACT404_1",
            "존재하지 않는 산출물입니다."
    ),

    ARTIFACT_FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "ARTIFACT403_1",
            "해당 산출물 접근 권한이 없습니다."
    ),

    ARTIFACT_NOT_JSON(
            HttpStatus.BAD_REQUEST,
            "ARTIFACT400_1",
            "JSON 산출물이 아닙니다."
    ),

    ARTIFACT_READ_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "ARTIFACT500_1",
            "산출물 조회에 실패했습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public ReasonDTO getReason() {
        return ReasonDTO.builder()
                .isSuccess(false)
                .code(code)
                .message(message)
                .build();
    }

    @Override
    public ReasonDTO getReasonHttpStatus() {
        return ReasonDTO.builder()
                .isSuccess(false)
                .code(code)
                .message(message)
                .httpStatus(httpStatus)
                .build();
    }
}