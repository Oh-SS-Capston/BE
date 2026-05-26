package com.example.ossdoc.global.llm.service.support;

/**
 * LLM 프롬프트 템플릿 정적 카탈로그.
 */
public final class LlmPromptCatalog {

    private LlmPromptCatalog() {
    }

    public static final String KOREAN_POLICY = """
            - 자연어 출력은 반드시 한국어로 작성한다.
            - JSON만 출력하고 마크다운/코드블록은 금지한다.
            - 근거가 없으면 '추정'으로 표기하거나 해당 문장을 제거한다.
            - 규칙 ID 자체를 설명 중심으로 그대로 노출하지 말고 사용자 주의사항으로 변환한다.
            """;

    public static final String PROMPT_CAUTIONS = """
            역할: 구조 시드를 바탕으로 "사용자 주의사항/예외"를 작성한다.
            목표: 코드 내부 규칙명을 그대로 복제하지 말고, 실제 사용자가 실수하기 쉬운 포인트를 안내한다.
            제약:
            1) cautions는 최대 %d개
            2) 각 항목은 title(짧게), message(1~2문장), when(언제 주의할지) 포함
            3) relatedClass/relatedMethod/evidenceIds를 가능한 범위에서 채운다.
            4) evidenceIds는 시드에 제공된 "ev_" 형식 문자열 ID를 그대로 사용한다.
            출력 스키마(JSON):
            {"cautions":[{"cautionId":"CAU-001","title":"string","message":"string","when":"string",
            "relatedClass":"pkg.Type","relatedMethod":"pkg.Type.method","evidenceIds":["ev_abc123"],"confidence":0.0}]}
            """;

    public static final String PROMPT_CAUTIONS_COMPACT = """
            역할: 주의사항/예외를 짧게 요약한다.
            제약:
            1) cautions 최대 %d개
            2) message는 100자 이내
            3) 중복 항목 제거
            4) evidenceIds는 시드에 제공된 "ev_" 형식 문자열 ID를 그대로 사용한다.
            출력 스키마(JSON):
            {"cautions":[{"cautionId":"CAU-001","title":"string","message":"string","when":"string",
            "relatedClass":"pkg.Type","relatedMethod":"pkg.Type.method","evidenceIds":["ev_abc123"],"confidence":0.0}]}
            """;

    public static final String PROMPT_SCENARIOS = """
            역할: 오픈소스 첫 사용자를 위한 "시작 가이드 시나리오"를 작성한다.
            반드시 아래 출력 계약을 따른다.
            1) overview: 문제/목적/적합한 사용 상황/핵심 기능/시작 가이드
            2) scenarios: 단계별 흐름(각 단계는 classFqn, methodFqn, evidenceLinks 연결)
            3) methodFlow: 실제 호출 순서(order, title, methodFqn)
            제약:
            - scenarios 최대 %d개
            - scenario 당 steps 최대 %d개
            출력 스키마(JSON):
            {"overview":{"project":"string","purpose":"string","fitSituation":"string","coreFeatures":"string","startGuide":"string"},
            "scenarios":[{"scenarioId":"SCN-001","title":"string","intent":"string",
            "steps":[{"stepNo":1,"description":"string","classFqn":"pkg.Type","methodFqn":"pkg.Type.method",
            "evidenceLinks":[{"evidenceId":1,"filePath":"path","lines":"10-20"}]}]}],
            "methodFlow":[{"order":1,"title":"string","description":"string","methodFqn":"pkg.Type.method"}]}
            """;

    public static final String PROMPT_SCENARIOS_COMPACT = """
            역할: 시작 시나리오를 compact 모드로 작성한다.
            제약:
            - scenarios 최대 %d개
            - 각 step 설명은 1문장
            - 근거 링크가 없는 문장은 최소화
            출력 스키마(JSON):
            {"overview":{"project":"string","purpose":"string","fitSituation":"string","coreFeatures":"string","startGuide":"string"},
            "scenarios":[{"scenarioId":"SCN-001","title":"string","intent":"string",
            "steps":[{"stepNo":1,"description":"string","classFqn":"pkg.Type","methodFqn":"pkg.Type.method",
            "evidenceLinks":[{"evidenceId":1,"filePath":"path","lines":"10-20"}]}]}],
            "methodFlow":[{"order":1,"title":"string","description":"string","methodFqn":"pkg.Type.method"}]}
            """;
}
