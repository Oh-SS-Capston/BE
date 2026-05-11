package com.example.ossdoc.domain.classmap.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ClassMapErrorCode implements BaseCode {
    CLASS_MAP_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "CLASSMAP404_1", "존재하지 않는 run 입니다."),
    CLASS_MAP_FORBIDDEN(HttpStatus.FORBIDDEN, "CLASSMAP403_1", "해당 run 접근 권한이 없습니다."),
    CLASS_MAP_NO_VISIBLE_TYPES(HttpStatus.BAD_REQUEST, "CLASSMAP400_1", "표시 가능한 타입이 없습니다."),
    CLASS_MAP_SUBSYSTEMS_NOT_READY(HttpStatus.BAD_REQUEST, "CLASSMAP400_2", "군집화 결과가 아직 생성되지 않았습니다."),
    CLASS_MAP_SUBSYSTEM_ID_REQUIRED(HttpStatus.BAD_REQUEST, "CLASSMAP400_3", "subsystemId가 필요합니다."),
    CLASS_MAP_SUBSYSTEM_NOT_FOUND(HttpStatus.NOT_FOUND, "CLASSMAP404_2", "존재하지 않는 subsystem 입니다."),
    CLASS_MAP_BUILD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLASSMAP500_1", "class map 생성에 실패했습니다."),
    CLASS_MAP_ARTIFACT_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLASSMAP500_2", "class map 산출물 저장에 실패했습니다.");

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
