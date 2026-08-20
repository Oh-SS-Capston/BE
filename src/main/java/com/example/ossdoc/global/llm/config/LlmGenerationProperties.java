package com.example.ossdoc.global.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 생성 단계 튜닝 값.
 * yaml prefix: {@code ossdoc.llm.generation}
 *
 * <p>기본값은 기존 LlmService 하드코딩 값과 동일하다.
 * 로컬 CPU 모델처럼 생성 속도가 느린 환경에서는 yaml에서 낮춰 잡는다.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.llm.generation")
public class LlmGenerationProperties {

    /**
     * step ①에서 생성할 주의사항 최대 개수.
     */
    private int maxCautions = 12;

    /**
     * step ②에서 생성할 시나리오 최대 개수.
     */
    private int maxScenarios = 4;

    /**
     * 시나리오 하나당 단계 최대 개수.
     */
    private int maxStepsPerScenario = 8;

    /**
     * step ① 출력 토큰 상한. 제공자 설정 상한과 min()으로 clamp된다.
     */
    private int tokensCautions = 16000;

    /**
     * step ② 출력 토큰 상한. 제공자 설정 상한과 min()으로 clamp된다.
     */
    private int tokensScenarios = 20000;

    /**
     * step ②를 한 번에 생성할지, 시나리오마다 따로 호출할지.
     *
     * <p>{@code SINGLE}이 5차까지의 방식이다. 골격 11칸을 한 호출로 채우게 하는데,
     * 모델의 "다 썼다"는 판단이 칸 수가 아니라 산문 흐름을 기준으로 작동해서
     * 4장 중 1장만 쓰고 멈추는 일이 반복됐다. 프롬프트로 세 번 말려 봤지만
     * 말릴수록 생산량이 줄었다.</p>
     *
     * <p>{@code PER_SCENARIO}는 그 판단이 낄 자리를 없앤다. 호출 하나가 시나리오 하나라
     * 순회는 for문이 하고 모델은 한 칸만 본다. 대신 호출마다 프롬프트가 재처리되므로
     * <b>컨텍스트 필터링이 필수 전제</b>다 — 필터링 없이 쪼개면 8차 baseline 실측 기준
     * step ② 프롬프트 시간이 14.2분에서 71분으로 늘어난다.</p>
     *
     * <p>9차 실측(junit-framework, 8차와 같은 입력)으로 기본을 PER_SCENARIO로 두었다.
     * 서술 칸 채움 36/88 → 85/88, step ② 벽시계 49.6분 → 28.5분, 출력 토큰 5,000 → 2,954,
     * 골격 밖 시나리오 폐기 6/10 → 0. 토큰을 덜 쓰고 결과가 나아진다.
     * 특히 SINGLE에서 11칸 전부 비어 있던 precondition/userAction/dataHandled가 회복했다.</p>
     */
    private ScenarioCallMode scenarioCallMode = ScenarioCallMode.PER_SCENARIO;

    /**
     * PER_SCENARIO 모드에서 시나리오 하나당 출력 토큰 상한.
     *
     * <p>골격 한 장은 step 최대 {@link #maxStepsPerScenario}개이고 step마다 서술 8필드다.
     * 8차 baseline에서 채워진 칸의 실측 분량이 step당 약 150토큰이라
     * 6 step × 150 ≈ 900에 여유를 둔다.</p>
     */
    private int tokensPerScenario = 1600;

    /**
     * PER_SCENARIO 모드에서 overview 호출의 출력 토큰 상한.
     * overview는 8필드짜리 단문이라 짧다. 짧아서 조기종료 위험도 없다.
     */
    private int tokensOverview = 900;

    /**
     * true면 이전 실행이 남긴 {@code llm/refined_rules.json}을 재사용하고 step ①을 건너뛴다.
     *
     * <p>step ② 실험 전용 스위치다. step ①은 프롬프트가 고정된 동안 결정론적이라
     * 8차 baseline에서 5차와 바이트 동일하게 재현됐는데, 그 확인 한 번을 위해
     * 실험마다 40분을 다시 쓰고 있었다. step ② A/B는 여러 번 돌려야 하므로
     * 이 40분이 실험 설계 자체를 제약한다.</p>
     *
     * <p>기본은 false다. 켜면 step ①이 <b>측정되지 않은 채</b> 흘러가므로
     * 프롬프트나 시드를 바꾼 뒤에는 반드시 꺼서 한 번은 정직하게 돌려야 한다.
     * 산출물을 어디서 읽을지가 로컬 경로에 묶여 있어 {@code local-only}일 때만 동작한다.</p>
     */
    private boolean reuseCautions = false;

    /**
     * step ② 호출 분해 방식.
     */
    public enum ScenarioCallMode {
        /** 한 호출로 전체 골격을 채운다. 5차까지의 방식이자 A/B 기준선. */
        SINGLE,
        /** overview 1회 + 시나리오마다 1회. 조기종료가 구조적으로 불가능하다. */
        PER_SCENARIO
    }
}
