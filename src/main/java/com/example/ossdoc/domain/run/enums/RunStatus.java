package com.example.ossdoc.domain.run.enums;

public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCESS,

    /*
     * 필수 단계는 성공했지만 선택 단계 일부가 실패한 상태.
     *
     * 예:
     * - SNAPSHOT / BUILD / EXTRACTION / GRAPHSTORE 성공
     * - CLUSTER 실패
     * - CLASSMAP 성공 또는 실패
     *
     * 프론트는 성공한 artifact는 보여주고,
     * 실패한 영역은 "~에 실패했습니다."로 표시.
     */
    PARTIAL_SUCCESS,
    FAILED
}