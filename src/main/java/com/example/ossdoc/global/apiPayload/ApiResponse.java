package com.example.ossdoc.global.apiPayload;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import com.example.ossdoc.global.apiPayload.code.status.SuccessStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({"isSuccess", "code", "message", "result"})
@Schema(description = "공통 응답 포맷")
public class ApiResponse<T> {

    @JsonProperty("isSuccess")
    @Schema(description = "성공 여부", example = "true")
    private Boolean isSuccess;

    @Schema(description = "상태 코드", example = "COMMON200")
    private String code;

    @Schema(description = "상태 메시지", example = "성공입니다.")
    private String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "결과 데이터")
    private T result;

    /**
     * 성공 응답 생성 (기본값: SuccessStatus._OK)
     * @param result 응답 데이터
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> onSuccess(T result) {
        return of(SuccessStatus._OK, result);
    }

    /**
     * 성공 응답 생성 (커스텀 코드 사용)
     * @param code BaseCode 구현체
     * @param result 응답 데이터
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> of(BaseCode code, T result) {
        ReasonDTO reason = code.getReasonHttpStatus();
        return new ApiResponse<>(
                reason.getIsSuccess(),
                reason.getCode(),
                reason.getMessage(),
                result
        );
    }

    /**
     * 실패 응답 생성 (BaseCode 사용)
     * @param code BaseCode 구현체 (에러 상태)
     * @param data 추가 데이터 (에러 상세정보)
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> onFailure(BaseCode code, T data) {
        ReasonDTO reason = code.getReasonHttpStatus();
        return new ApiResponse<>(
                reason.getIsSuccess(),
                reason.getCode(),
                reason.getMessage(),
                data
        );
    }
}
