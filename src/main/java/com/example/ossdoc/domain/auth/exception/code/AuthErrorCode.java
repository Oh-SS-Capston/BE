package com.example.ossdoc.domain.auth.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseCode {

    AUTHENTICATION_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_1",
            "인증이 필요합니다."
    ),

    USER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "AUTH404_1",
            "사용자를 찾을 수 없습니다."
    ),

    OAUTH2_LOGIN_FAILED(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_2",
            "Google 로그인에 실패했습니다."
    ),

    GOOGLE_EMAIL_NOT_VERIFIED(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_3",
            "Google에서 인증되지 않은 이메일입니다."
    ),

    INACTIVE_USER(
            HttpStatus.FORBIDDEN,
            "AUTH403_1",
            "탈퇴한 계정입니다."
    ),

    REJOIN_WAIT_PERIOD_NOT_PASSED(
            HttpStatus.FORBIDDEN,
            "AUTH403_2",
            "회원 탈퇴 후 재가입 대기기간이 지나지 않았습니다."
    ),

    DUPLICATE_NICKNAME(
            HttpStatus.CONFLICT,
            "AUTH409_1",
            "이미 사용 중인 닉네임입니다."
    ),

    INVALID_NICKNAME(
            HttpStatus.BAD_REQUEST,
            "AUTH400_1",
            "사용할 수 없는 닉네임입니다."
    ),

    INVALID_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_4",
            "유효하지 않은 토큰입니다."
    ),

    EXPIRED_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_5",
            "만료된 토큰입니다."
    ),

    INVALID_TOKEN_TYPE(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_6",
            "잘못된 토큰 타입입니다."
    ),

    REFRESH_TOKEN_NOT_FOUND(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_7",
            "리프레시 토큰이 없습니다."
    ),

    REFRESH_TOKEN_MISMATCH(
            HttpStatus.UNAUTHORIZED,
            "AUTH401_8",
            "리프레시 토큰이 일치하지 않습니다."
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