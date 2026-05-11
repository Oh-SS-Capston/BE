package com.example.ossdoc.domain.rule.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RuleCandidateErrorCode implements BaseCode {

    INVALID_RULE_MINE_REQUEST(
            HttpStatus.BAD_REQUEST,
            "RULE_400_001",
            "Rule candidate mining request is invalid."
    ),

    RUN_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RULE_404_001",
            "Repo run not found."
    ),

    RUN_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "RULE_403_001",
            "No permission to access this run."
    ),

    GRAPHSTORE_DATA_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RULE_404_002",
            "GraphStore data not found for this run."
    ),

    RULE_SIGNAL_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RULE_404_003",
            "Rule mining signal not found."
    ),

    RULE_CANDIDATE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RULE_404_004",
            "Rule candidate not found."
    ),

    RULE_CANDIDATE_ARTIFACT_PUBLISH_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "RULE_500_001",
            "Failed to publish rule candidate artifact."
    );

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