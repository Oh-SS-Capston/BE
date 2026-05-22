package com.example.ossdoc.domain.cluster.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ClusterErrorCode implements BaseCode {

    CLUSTER_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "CLUSTER404_1", "존재하지 않는 run 입니다."),
    CLUSTER_GRAPH_EMPTY(HttpStatus.BAD_REQUEST, "CLUSTER400_1", "군집화할 graph 데이터가 없습니다."),
    CLUSTER_FORBIDDEN(HttpStatus.FORBIDDEN, "CLUSTER403_1", "해당 run에 접근할 권한이 없습니다."),
    CLUSTER_PUBLIC_API_EMPTY(HttpStatus.BAD_REQUEST, "CLUSTER400_2", "public_api_entry가 비어 있어 군집화 기준을 만들 수 없습니다."),
    CLUSTER_PROJECTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLUSTER500_1", "graph projection에 실패했습니다."),
    CLUSTER_LEIDEN_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLUSTER500_2", "Leiden 군집화에 실패했습니다."),
    CLUSTER_ASSEMBLY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLUSTER500_3", "subsystem 조립에 실패했습니다."),
    CLUSTER_RANKING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLUSTER500_4", "중요도 산정에 실패했습니다."),
    CLUSTER_ARTIFACT_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLUSTER500_5", "cluster 산출물 저장에 실패했습니다."),
    CLUSTER_REFINE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CLUSTER500_6", "subsystem 보정에 실패했습니다.");

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
