package com.example.ossdoc.global.s3.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum S3ErrorCode implements BaseCode {

    // 업로드 실패
    UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "S3_500_001", "S3 파일 업로드에 실패했습니다."),

    // 삭제 실패
    DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "S3_500_002", "S3 파일 삭제에 실패했습니다."),

    // JSON 직렬화 실패 (업로드 전처리 단계)
    JSON_SERIALIZE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "S3_500_003", "Artifact JSON 직렬화에 실패했습니다."),

    // 잘못된 S3 키/URL
    INVALID_S3_KEY(HttpStatus.BAD_REQUEST,
            "S3_400_001", "유효하지 않은 S3 경로입니다.");

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