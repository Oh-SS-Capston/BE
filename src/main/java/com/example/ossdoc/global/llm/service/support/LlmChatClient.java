package com.example.ossdoc.global.llm.service.support;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM 제공자 추상화.
 *
 * <p>LlmService는 "system prompt + user message를 주면 JSON 응답을 준다"는 계약만 알면 되고,
 * 실제 제공자(Anthropic Claude API / 로컬 Ollama)는 {@code ossdoc.llm.provider} 설정으로 결정된다.
 * 구현체는 {@code @ConditionalOnProperty}로 하나만 빈에 올라간다.</p>
 */
public interface LlmChatClient {

    /**
     * 실행에 사용할 1순위 모델 ID.
     * 산출물 추적을 위해 llm_scenario_cache.model 컬럼에 기록된다.
     */
    String resolvePrimaryModel();

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
