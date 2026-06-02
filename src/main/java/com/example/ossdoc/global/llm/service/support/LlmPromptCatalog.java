package com.example.ossdoc.global.llm.service.support;

/**
 * LLM prompt template catalog.
 */
public final class LlmPromptCatalog {

    private LlmPromptCatalog() {
    }

    public static final String KOREAN_POLICY = """
            - 자연어 출력은 반드시 한국어로 작성한다.
            - JSON만 출력하고 마크다운/코드블록은 금지한다.
            - 근거가 없으면 "추정"으로 표시하거나 해당 문장을 만들지 않는다.
            - 규칙 ID 자체를 설명 문장에 노출하지 말고, 사용자가 취할 행동으로 바꾼다.
            - 금지 표현 반복 사용 금지: "핵심 동작 수행", "입력 조건 기반 로직"
            - 문장 구성 원칙: 언제 + 무엇을 + 어떻게 확인/조치하는지 쓴다.
            - 코드 위치, evidenceId, classFqn, methodFqn이 있는 항목은 반드시 함께 보존한다.
            """;

    public static final String PROMPT_CAUTIONS = """
            역할: 구조 그래프와 rule candidate를 바탕으로 "사용 시 주의사항/예외/검증 포인트"를 작성한다.
            목표: 코드 규칙명을 복제하지 말고, 실제 사용자가 호출 전에 확인할 행동과 실패 징후를 설명한다.
            제약:
            1) cautions는 최대 %d개
            2) 각 항목은 title, message, when, impact, rationale, normalFlow, failureSignal, userAction,
               evidenceInterpretation, confidenceReason을 가능한 범위에서 포함한다.
            3) message는 단순 요약이 아니라 "언제 + 무엇이 문제인지 + 어떻게 확인/조치할지"를 포함한다.
            4) relatedClass/relatedMethod/evidenceIds는 제공된 값을 임의 생성하지 말고 가능한 범위에서 채운다.
            5) evidenceIds는 입력에 제공된 ID를 그대로 사용한다.
            6) 근거가 약한 해석은 confidence를 낮추고 confidenceReason에 이유를 쓴다.
            7) 금지 표현("핵심 동작 수행", "입력 조건 기반 로직")은 그대로 사용하지 않는다.
            출력 스키마(JSON):
            {"cautions":[{"cautionId":"CAU-001","title":"string","message":"string","when":"string",
            "impact":"string","rationale":"string","normalFlow":"string","failureSignal":"string",
            "userAction":"string","evidenceInterpretation":"string","confidenceReason":"string",
            "summary":{"condition":"string","action":"string"},"relatedClass":"pkg.Type",
            "relatedMethod":"pkg.Type.method","evidenceIds":["ev_abc123"],"confidence":0.0}]}
            """;

    public static final String PROMPT_CAUTIONS_COMPACT = """
            역할: 주의사항/예외를 compact 모드로 작성한다.
            제약:
            1) cautions 최대 %d개
            2) 각 항목은 message, when, failureSignal, userAction, evidenceInterpretation을 우선 보존한다.
            3) 중복 항목은 제거한다.
            4) evidenceIds는 입력에 제공된 ID를 그대로 사용한다.
            출력 스키마(JSON):
            {"cautions":[{"cautionId":"CAU-001","title":"string","message":"string","when":"string",
            "impact":"string","rationale":"string","normalFlow":"string","failureSignal":"string",
            "userAction":"string","evidenceInterpretation":"string","confidenceReason":"string",
            "summary":{"condition":"string","action":"string"},"relatedClass":"pkg.Type",
            "relatedMethod":"pkg.Type.method","evidenceIds":["ev_abc123"],"confidence":0.0}]}
            """;

    public static final String PROMPT_SCENARIOS = """
            역할: 처음 사용하는 개발자 관점의 "시작 가이드 시나리오"와 API 사용 흐름을 작성한다.
            반드시 아래 출력 계약을 따른다.
            1) overview: 문제/목적/적합한 사용 상황/핵심 기능/시작 가이드/아키텍처 요약/데이터 흐름
            2) scenarios: 각 단계의 classFqn, methodFqn, evidenceLinks, 근거 해석, 성공/실패 신호
            3) methodFlow: 실제 호출 순서(order, title, methodFqn)와 단계별 전제/결과/위험
            제약:
            - scenarios 최대 %d개
            - scenario 당 steps 최대 %d개
            - step.description은 호출 전 체크 -> 호출 -> 성공 신호 -> 실패 시 조치 흐름을 반영한다.
            - step.evidenceInterpretation에는 해당 코드 위치가 왜 이 단계의 근거인지 설명한다.
            - 근거 링크가 없는 단계는 만들지 말거나 confidence를 낮추고 "추정"으로 표시한다.
            - 금지 표현("핵심 동작 수행", "입력 조건 기반 로직")은 그대로 사용하지 않는다.
            출력 스키마(JSON):
            {"overview":{"project":"string","purpose":"string","fitSituation":"string","coreFeatures":"string",
            "startGuide":"string","architectureSummary":"string","dataFlow":"string","confidenceNote":"string"},
            "scenarios":[{"scenarioId":"SCN-001","title":"string","intent":"string","whyThisMatters":"string",
            "entryPoint":"string","expectedOutcome":"string",
            "steps":[{"stepNo":1,"description":"string","precondition":"string","action":"string",
            "successSignal":"string","failureSignal":"string","userAction":"string","dataHandled":"string",
            "evidenceInterpretation":"string","classFqn":"pkg.Type","methodFqn":"pkg.Type.method",
            "confidence":0.0,"evidenceLinks":[{"evidenceId":1,"filePath":"path","lines":"10-20"}]}]}],
            "methodFlow":[{"order":1,"title":"string","description":"string","precondition":"string",
            "result":"string","risk":"string","evidenceInterpretation":"string","methodFqn":"pkg.Type.method"}]}
            """;

    public static final String PROMPT_SCENARIOS_COMPACT = """
            역할: 시작 시나리오를 compact 모드로 작성한다.
            제약:
            - scenarios 최대 %d개
            - 각 step은 description, action, successSignal, failureSignal, evidenceInterpretation을 우선 보존한다.
            - 근거 링크가 없는 문장은 최소화한다.
            출력 스키마(JSON):
            {"overview":{"project":"string","purpose":"string","fitSituation":"string","coreFeatures":"string",
            "startGuide":"string","architectureSummary":"string","dataFlow":"string","confidenceNote":"string"},
            "scenarios":[{"scenarioId":"SCN-001","title":"string","intent":"string","whyThisMatters":"string",
            "entryPoint":"string","expectedOutcome":"string",
            "steps":[{"stepNo":1,"description":"string","precondition":"string","action":"string",
            "successSignal":"string","failureSignal":"string","userAction":"string","dataHandled":"string",
            "evidenceInterpretation":"string","classFqn":"pkg.Type","methodFqn":"pkg.Type.method",
            "confidence":0.0,"evidenceLinks":[{"evidenceId":1,"filePath":"path","lines":"10-20"}]}]}],
            "methodFlow":[{"order":1,"title":"string","description":"string","precondition":"string",
            "result":"string","risk":"string","evidenceInterpretation":"string","methodFqn":"pkg.Type.method"}]}
            """;
}
