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
            역할: 입력의 scenarioSeed에 담긴 시나리오 골격을 받아, 비어 있는 서술 필드를 채운다.
            골격은 코드 분석으로 이미 확정된 것이다. 시나리오를 새로 발명하지 않는다.
            반드시 아래 출력 계약을 따른다.
            1) overview: 문제/목적/적합한 사용 상황/핵심 기능/시작 가이드/아키텍처 요약/데이터 흐름
            2) scenarios: scenarioSeed의 각 단계에 서술을 채운 결과
            3) methodFlow: 실제 호출 순서(order, title, methodFqn)와 단계별 전제/결과/위험
            골격 유지 규칙:
            - scenarioSeed의 scenarioId, 단계 순서(stepNo), classFqn, methodFqn을 그대로 옮긴다. 바꾸지 않는다.
            - 시나리오나 단계를 추가하지도 빼지도 않는다. 골격에 있는 것만 쓴다.
            - scenarios는 최대 %d개, scenario 당 steps는 최대 %d개다.
            서술 작성 규칙:
            - 각 step의 description, precondition, action, successSignal, failureSignal, userAction,
              evidenceInterpretation을 모두 채운다. 빈 문자열이나 생략으로 두지 않는다.
            - 각 단계의 근거는 골격이 준 summarySeed와 filePath/startLine이다. 그 범위를 넘는 내용은 쓰지 않는다.
            - step.evidenceInterpretation에는 해당 코드 위치가 왜 이 단계의 근거인지 설명한다.
            - evidenceLinks의 filePath/lines는 골격에 주어진 값을 그대로 옮긴다. 새 값을 지어내지 않는다.
            - 근거가 약하면 confidence를 낮추고 "추정"이라고 밝힌다.
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
            역할: 입력의 scenarioSeed 골격에 서술을 채운다. compact 모드다.
            제약:
            - scenarioSeed의 scenarioId, stepNo, classFqn, methodFqn을 그대로 옮긴다. 시나리오나 단계를 늘리지 않는다.
            - scenarios 최대 %d개
            - 각 step은 description, action, successSignal, failureSignal, evidenceInterpretation을 우선 채운다.
            - 골격의 summarySeed와 근거 위치를 벗어난 내용은 쓰지 않는다.
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

    /**
     * PER_SCENARIO 모드: 개요만 만드는 소형 호출.
     * 시나리오를 여기서 다루지 않으므로 "몇 개까지 썼나"를 모델이 판단할 일이 없다.
     */
    public static final String PROMPT_OVERVIEW = """
            역할: 입력 시드를 근거로 이 라이브러리의 개요 8필드를 채운다. 그 외에는 아무것도 만들지 않는다.
            규칙:
            - 시드(overviewSeed, coreClassSeed, cautions)에 없는 사실을 지어내지 않는다.
            - 각 필드는 두세 문장으로 쓴다. 빈 문자열로 두지 않는다.
            - 근거가 약한 문장은 confidenceNote에 어디가 추정인지 밝힌다.
            - 금지 표현("핵심 동작 수행", "입력 조건 기반 로직")은 그대로 사용하지 않는다.
            출력 스키마(JSON):
            {"overview":{"project":"string","purpose":"string","fitSituation":"string","coreFeatures":"string",
            "startGuide":"string","architectureSummary":"string","dataFlow":"string","confidenceNote":"string"}}
            """;

    /**
     * PER_SCENARIO 모드: 시나리오 <b>한 장</b>의 빈칸만 채우는 호출.
     *
     * <p>SINGLE 모드 프롬프트와 결정적으로 다른 점은 "몇 개를 쓸지"를 아예 묻지 않는다는 것이다.
     * 골격이 한 장뿐이므로 모델이 순회를 관리할 필요가 없고, 따라서 중간에 "다 썼다"고
     * 판단할 자리도 없다. 순회는 호출자가 for문으로 한다.</p>
     *
     * <p>{@code excludedByOtherScenarios}는 이 시나리오 골격에서 빠진 메서드와 그것을 가져간
     * 시나리오를 알려준다. 골격은 같은 메서드를 두 시나리오에 넣지 않기 때문에, 이 정보가 없으면
     * 격리된 모델이 "앞서 만든 객체를" 같은 근거 없는 서술로 빈자리를 메운다.</p>
     *
     * <p>근거 목록에서 {@code summarySeed}를 유일한 축으로 삼지 않는다. 예전에는 근거를
     * "summarySeed와 filePath/startLine, 그리고 evidence"로 못 박았는데, 실측 40개 메서드 중
     * 39개는 실제 요약이 없어 {@code summarySeed}가 이름 패턴 추측 문구였다. 모델은 지시대로
     * 그것을 근거 삼아 베꼈고, 그 결과가 산출물의 채움말이었다. 시드에서 추측 문구를 걷어냈으므로
     * (그 자리는 이제 빈다) 프롬프트도 실제로 존재하는 근거를 가리켜야 한다.</p>
     */
    public static final String PROMPT_SCENARIO_ONE = """
            역할: 아래 시나리오 골격 한 장의 비어 있는 서술 필드를 채운다.
            골격은 코드 분석으로 확정된 것이다. 시나리오를 새로 만들지 않는다.
            채울 칸:
            - steps의 각 항목마다 description, precondition, action, successSignal, failureSignal,
              userAction, dataHandled, evidenceInterpretation을 여덟 개 모두 채운다.
              하나라도 빈 문자열이나 생략으로 두지 않는다. 이 여덟 개가 이 작업의 전부다.
            - 시나리오 수준의 whyThisMatters, entryPoint, expectedOutcome도 채운다.
            골격 유지 규칙:
            - scenarioId와 각 step의 stepNo, methodFqn을 그대로 옮긴다. 서술을 붙일 칸을 가리킨다.
            - 골격에 없는 step을 추가하지 않는다. 다른 시나리오를 만들지 않는다.
            - classFqn과 evidenceLinks는 응답에 넣지 않는다. 골격 값이 쓰이므로 버려진다.
            서술 작성 규칙:
            - 근거는 골격이 준 signatureHint(메서드 시그니처), filePath/startLine, evidence(코드 스니펫),
              그리고 summarySeed가 있을 때 그것뿐이다. 그 범위를 넘지 않는다.
            - summarySeed는 없을 수 있다. 없으면 signatureHint와 evidence로 쓴다.
              시그니처의 인자 이름·타입과 스니펫의 실제 코드가 가장 확실한 근거다.
            - excludedByOtherScenarios에 있는 메서드는 다른 시나리오가 다룬다.
              이 시나리오의 step으로 삼지 말고, 필요하면 "그 시나리오에서 다룬다"고만 언급한다.
            - 근거가 약하면 confidence를 낮추고 "추정"이라고 밝힌다.
            - 금지 표현("핵심 동작 수행", "입력 조건 기반 로직")은 그대로 사용하지 않는다.
            출력 스키마(JSON):
            {"scenarios":[{"scenarioId":"%s","title":"string","intent":"string","whyThisMatters":"string",
            "entryPoint":"string","expectedOutcome":"string",
            "steps":[{"stepNo":1,"description":"string","precondition":"string","action":"string",
            "successSignal":"string","failureSignal":"string","userAction":"string","dataHandled":"string",
            "evidenceInterpretation":"string","confidenceReason":"string",
            "methodFqn":"pkg.Type.method","confidence":0.0}]}]}
            """;
}
