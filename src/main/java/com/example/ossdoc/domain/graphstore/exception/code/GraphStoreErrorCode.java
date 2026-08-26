package com.example.ossdoc.domain.graphstore.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GraphStoreErrorCode implements BaseCode {

    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "GRAPH_404_001", "Repo run not found."),
    RUN_ACCESS_DENIED(HttpStatus.FORBIDDEN, "GRAPH_403_001", "No permission to access this run."),
    FACTS_ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND, "GRAPH_404_002", "facts artifact not found."),
    FACTS_READ_FAILED(HttpStatus.BAD_REQUEST, "GRAPH_400_001", "Failed to read facts artifact."),
    INVALID_FACTS_SCHEMA(HttpStatus.BAD_REQUEST, "GRAPH_400_002", "Invalid facts schema.");

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