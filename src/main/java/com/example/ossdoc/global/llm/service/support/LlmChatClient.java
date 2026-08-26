package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.enums.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM 제공자 추상화.
 *
 * <p>LlmService는 "system prompt + user message를 주면 JSON 응답을 준다"는 계약만 알면 되고,
 * 실제 제공자(Anthropic Claude API / 로컬 Ollama) 선택은 {@link LlmChatClientResolver}가 맡는다.
 * 구현체는 모두 빈으로 등록되며, run이 지정한 provider가 없으면
 * {@code ossdoc.llm.provider} 설정값이 기본으로 쓰인다.</p>
 */
public interface LlmChatClient {

    /**
     * 실행에 사용할 1순위 모델 ID.
     * 산출물 추적을 위해 llm_scenario_cache.model 컬럼에 기록된다.
     */
    String resolvePrimaryModel();

    /** 이 구현이 담당하는 제공자. Resolver가 이 값으로 구현을 찾는다. */
    LlmProvider provider();

    /**
     * 지금 이 환경에서 실제로 호출할 수 있는지.
     *
     * <p>빈으로 떠 있다는 것과 쓸 수 있다는 것은 다르다. claude 구현은 API 키가 없으면
     * 빈은 있지만 호출은 전부 실패하므로, 그 상태를 요청 시점에 걸러내기 위한 것이다.</p>
     */
    default boolean available() {
        return true;
    }

    /**
     * 프롬프트를 보내고 JSON 응답을 받는다.
     *
     * @param stepName    로그 식별용 단계 이름
     * @param systemPrompt 역할/제약/출력 스키마를 담은 system 프롬프트
     * @param userMessage  구조 시드와 근거를 담은 컨텍스트 JSON 문자열
     * @param maxTokens    이 단계가 요청하는 출력 토큰 상한 (제공자 설정 상한과 함께 clamp된다)
     * @return 파싱된 JSON 응답
     */
    JsonNode call(String stepName, String systemPrompt, String userMessage, int maxTokens);
}
