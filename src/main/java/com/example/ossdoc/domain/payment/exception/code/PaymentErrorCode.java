package com.example.ossdoc.domain.payment.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements BaseCode {

    PAYMENT_ORDER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "PAYMENT404_1",
            "결제 요청 정보를 찾을 수 없습니다."
    ),

    PAYMENT_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "PAYMENT403_1",
            "해당 결제 요청에 접근할 권한이 없습니다."
    ),

    PAYMENT_ALREADY_PROCESSED(
            HttpStatus.CONFLICT,
            "PAYMENT409_1",
            "이미 처리된 결제입니다."
    ),

    PAYMENT_AMOUNT_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "PAYMENT400_1",
            "결제 금액이 일치하지 않습니다."
    ),

    PAYMENT_CURRENCY_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "PAYMENT400_2",
            "결제 통화가 일치하지 않습니다."
    ),

    PAYMENT_NOT_PAID(
            HttpStatus.BAD_REQUEST,
            "PAYMENT400_3",
            "결제가 완료되지 않았습니다."
    ),

    PAYMENT_PROVIDER_ERROR(
            HttpStatus.BAD_GATEWAY,
            "PAYMENT502_1",
            "결제 제공자 통신 중 오류가 발생했습니다."
    ),

    PAYMENT_CANCEL_FAILED(
            HttpStatus.BAD_GATEWAY,
            "PAYMENT502_2",
            "결제 취소 요청에 실패했습니다."
    ),

    PAYMENT_WEBHOOK_INVALID(
            HttpStatus.UNAUTHORIZED,
            "PAYMENT401_1",
            "유효하지 않은 결제 웹훅 요청입니다."
    ),

    PAYMENT_CONFIG_MISSING(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "PAYMENT500_1",
            "결제 설정값이 누락되었습니다."
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