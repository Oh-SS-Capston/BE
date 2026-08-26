package com.example.ossdoc.global.llm.enums;

import java.util.Locale;

/**
 * LLM 제공자.
 *
 * <p>기존에는 {@code ossdoc.llm.provider} 설정으로 기동 시점에 구현체 하나만 빈에 올렸다.
 * run 단위로 제공자를 고르려면 두 구현이 모두 떠 있어야 하므로, 어떤 구현을 쓸지 가리키는
 * 값이 타입으로 필요해졌다.</p>
 *
 * <p>요청 값은 사용자 입력이라 대소문자를 가리지 않고 받되, 모르는 값은 조용히 기본값으로
 * 흘리지 않고 null로 돌려 호출자가 판단하게 한다. 오타가 난 요청이 의도와 다른 모델로
 * 조용히 실행되면 비용과 산출물 품질이 함께 어긋난다.</p>
 */
public enum LlmProvider {

    /** 로컬 Ollama. 외부 비용이 없고 호스트 자원을 쓴다. */
    OLLAMA,

    /** Anthropic Claude API. 외부 호출 비용이 발생한다. */
    CLAUDE;

    /**
     * 문자열을 제공자로 바꾼다.
     *
     * @return 인식하지 못한 값이거나 비어 있으면 null
     */
    public static LlmProvider from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LlmProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
