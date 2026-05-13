package com.example.ossdoc.domain.run.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RunStage {
    QUEUED(
            0,
            true,
            "분석 요청이 대기 중입니다."
    ),

    SNAPSHOT(
            10,
            true,
            "레포지토리 스냅샷을 준비 중입니다."
    ),

    BUILD(
            25,
            true,
            "빌드 환경을 분석 중입니다."
    ),

    EXTRACTION(
            45,
            true,
            "소스 코드와 바이트코드를 분석 중입니다."
    ),

    GRAPHSTORE(
            65,
            true,
            "코드 구조 그래프를 저장 중입니다."
    ),

    CLUSTER(
            78,
            false,
            "주요 모듈과 군집을 분석 중입니다."
    ),

    PUBLICAPI(
            87,
            false,
            "공개 API 진입점·확장 포인트를 분석 중입니다."
    ),

    CLASSMAP(
            90,
            false,
            "클래스 다이어그램을 생성 중입니다."
    ),

    RULE(
            92,
            false,
            "규칙 후보를 추출 중입니다."
    ),

    LLM(
            97,
            false,
            "LLM 분석 결과를 생성 중입니다."
    ),

    DONE(
            100,
            false,
            "분석이 완료되었습니다."
    ),

    FAILED(
            100,
            true,
            "분석에 실패했습니다."
    );

    private final int progress;
    private final boolean required;
    private final String defaultMessage;
}