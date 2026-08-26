package com.example.ossdoc.domain.build.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BuildErrorCode implements BaseCode {
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "BUILD_001", "Run not found"),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "BUILD_002", "Workspace not found"),
    REPO_ROOT_NOT_FOUND(HttpStatus.NOT_FOUND, "BUILD_003", "Repo root not found"),
    BUILD_TOOL_NOT_FOUND(HttpStatus.BAD_REQUEST, "BUILD_004", "Build tool not found (no gradle/maven files)"),

    GRADLE_INIT_SCRIPT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "BUILD_010", "Gradle init script creation failed"),
    GRADLE_DUMP_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "BUILD_011", "Gradle project model dump failed"),
    GRADLE_COMPILE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "BUILD_012", "Gradle compile failed"),

    BUILD_MANIFEST_WRITE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "BUILD_020", "Failed to write build_manifest.json");

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
