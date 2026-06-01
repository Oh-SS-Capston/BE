package com.example.ossdoc.domain.token.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TokenErrorCode implements BaseCode {

    TOKEN_WALLET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "TOKEN404_1",
            "토큰 지갑을 찾을 수 없습니다."
    ),

    USER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "TOKEN404_2",
            "사용자를 찾을 수 없습니다."
    ),

    INVALID_TOKEN_AMOUNT(
            HttpStatus.BAD_REQUEST,
            "TOKEN400_1",
            "토큰 금액이 올바르지 않습니다."
    ),

    INSUFFICIENT_TOKEN(
            HttpStatus.PAYMENT_REQUIRED,
            "TOKEN402_1",
            "토큰이 부족합니다."
    ),

    DUPLICATE_TOKEN_CHARGE(
            HttpStatus.CONFLICT,
            "TOKEN409_1",
            "이미 처리된 토큰 충전입니다."
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
                .httpStatus(httpStatus)
                .isSuccess(false)
                .code(code)
                .message(message)
                .build();
    }
}