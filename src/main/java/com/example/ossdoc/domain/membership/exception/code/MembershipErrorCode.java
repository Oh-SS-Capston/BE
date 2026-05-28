package com.example.ossdoc.domain.membership.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MembershipErrorCode implements BaseCode {

    MEMBERSHIP_REQUIRED(
            HttpStatus.PAYMENT_REQUIRED,
            "MEMBERSHIP402_1",
            "무료 분석 기회를 모두 사용했습니다. 멤버십 가입 후 사용할 수 있습니다."
    ),

    SUBSCRIPTION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "MEMBERSHIP404_1",
            "구독 정보를 찾을 수 없습니다."
    ),

    SUBSCRIPTION_ALREADY_ACTIVE(
            HttpStatus.CONFLICT,
            "MEMBERSHIP409_1",
            "이미 활성화된 멤버십이 있습니다."
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