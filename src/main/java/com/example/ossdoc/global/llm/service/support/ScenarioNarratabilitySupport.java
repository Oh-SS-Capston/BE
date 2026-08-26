package com.example.ossdoc.global.llm.service.support;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 어떤 메서드가 <b>시나리오 서술을 가질 수 있는지</b>를 판정한다.
 *
 * <p>이 판정이 별도 클래스로 나온 이유는 두 곳이 같은 규칙을 써야 하기 때문이다.
 * 골격 생성({@code LlmInputAssemblerBuildSupport.appendClassScenarios})은 이 규칙으로
 * 시나리오 후보를 고르고, 품질 게이트({@code LlmServiceBuildSupport.buildApiDocQualityGate})는
 * 같은 규칙으로 "이 카드가 빈 것이 구조적 한계인지 개선 여지인지"를 가른다.</p>
 *
 * <p>규칙이 두 곳에 복제되면 한쪽만 바뀌는 순간 게이트가 거짓말을 시작한다. 이 저장소는
 * 이미 같은 실패를 겪었다 — 점수 산식이 두 벌 있었고 그중 하나만 targetSuitability를
 * 생산해서 필터가 조용히 항상 통과했다. 그래서 규칙을 한 곳에 둔다.</p>
 *
 * <p><b>이 판정은 run과 무관하다.</b> {@code max-scenarios} 상한 때문에 이번 실행의 골격에
 * 들어가지 못한 클래스는 여기서 "서술 불가"가 아니다. 그건 튜닝으로 풀리는 문제이고
 * 지표에서 사라지면 안 된다. 여기서 걸러지는 것은 저장소의 구조 때문에 무엇을 해도
 * 흐름을 만들 수 없는 것뿐이다.</p>
 */
public final class ScenarioNarratabilitySupport {

    /**
     * 단계가 이보다 적으면 시나리오로 만들지 않는다. 1단계짜리는 흐름이 아니다.
     *
     * <p>골격 생성과 게이트가 공유한다. 이 값을 바꾸면 "서술을 가질 수 있는 카드"의
     * 정의가 함께 바뀌므로 두 곳이 동시에 따라와야 한다.</p>
     */
    public static final int MIN_SCENARIO_STEPS = 2;

    /** {@link #unscorableReason} 값: 클래스의 distinct 메서드가 모자라 흐름을 만들 수 없다. */
    public static final String REASON_SINGLE_METHOD_CLASS = "singleMethodClass";

    /** {@link #unscorableReason} 값: {@code Object}에서 온 메서드라 사용 절차의 단계가 아니다. */
    public static final String REASON_OBJECT_METHOD = "objectMethod";

    private ScenarioNarratabilitySupport() {
    }

    /**
     * 클래스·메서드 식별에 필요한 최소 표현.
     *
     * <p>골격 쪽은 {@code CoreMethodSeed}를, 게이트 쪽은 카드 JSON을 들고 있다.
     * 공통 타입을 하나 두고 각자 자기 타입에서 옮겨 담는다. 복제되는 것은 세 필드를
     * 옮기는 코드뿐이고 판정 규칙 자체는 복제되지 않는다.</p>
     */
    public record MethodRef(String classFqn, String fqn, String methodName) {
    }

    /**
     * {@code Object}에서 물려받은 메서드인지.
     *
     * <p>이름 기반 판정은 여기까지만 한다. getter나 factory를 이름으로 걸러내면
     * 그것이 곧 특정 라이브러리 형태에 대한 과적합이 된다 — 어떤 라이브러리에서는
     * getter가 공개 API의 본체다.</p>
     */
    public static boolean isObjectMethod(String methodName) {
        String name = methodName == null ? "" : methodName.trim();
        return "hashCode".equals(name) || "equals".equals(name) || "toString".equals(name);
    }

    /**
     * 시나리오 단계를 {@link #MIN_SCENARIO_STEPS}개 이상 낼 수 있는 클래스의 fqn 집합.
     *
     * <p>오버로드는 fqn이 같으므로 한 번만 센다. 단계로 쓸 때도 한 번만 쓰기 때문이다.</p>
     */
    public static Set<String> narratableClassFqns(Collection<MethodRef> methods) {
        Map<String, Set<String>> distinctFqnByClass = new LinkedHashMap<>();
        for (MethodRef method : methods) {
            if (method == null || isBlank(method.classFqn()) || isBlank(method.fqn())) {
                continue;
            }
            if (isObjectMethod(method.methodName())) {
                continue;
            }
            distinctFqnByClass
                    .computeIfAbsent(method.classFqn(), key -> new LinkedHashSet<>())
                    .add(method.fqn());
        }

        Set<String> narratable = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : distinctFqnByClass.entrySet()) {
            if (entry.getValue().size() >= MIN_SCENARIO_STEPS) {
                narratable.add(entry.getKey());
            }
        }
        return narratable;
    }

    /** 이 메서드가 시나리오 서술을 가질 수 있는지. */
    public static boolean isNarratable(MethodRef method, Set<String> narratableClasses) {
        return method != null
                && !isObjectMethod(method.methodName())
                && narratableClasses.contains(method.classFqn());
    }

    /**
     * 서술을 가질 수 없는 이유. 게이트가 이 값을 그대로 집계해 산출물에 남긴다.
     *
     * <p>이유를 함께 내는 것이 중요하다. 채점 대상에서 빠진 수만 남기면 "왜 빠졌는지"를
     * 산출물만 보고는 알 수 없고, 그러면 다음 사람이 그 수를 줄이려고 튜닝을 시도한다.
     * 튜닝으로 줄지 않는 수라는 사실이 함께 있어야 한다.</p>
     *
     * @return {@link #REASON_OBJECT_METHOD} 또는 {@link #REASON_SINGLE_METHOD_CLASS}
     */
    public static String unscorableReason(MethodRef method, Set<String> narratableClasses) {
        if (method != null && isObjectMethod(method.methodName())) {
            return REASON_OBJECT_METHOD;
        }
        return REASON_SINGLE_METHOD_CLASS;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
