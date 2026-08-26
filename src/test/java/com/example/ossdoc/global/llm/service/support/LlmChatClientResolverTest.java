package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.enums.LlmProvider;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * run 단위 제공자 선택 검증.
 *
 * <p>조용히 다른 모델로 대체되는 경우가 없어야 한다. 대체되면 비용과 산출물 품질이 함께
 * 달라지는데 로그 말고는 드러나는 곳이 없고, 캐시 키도 요청한 제공자로 굳어 잘못된 재사용을 만든다.</p>
 */
class LlmChatClientResolverTest {

    @Test
    void 지정이_없으면_설정_기본값을_쓴다() {
        LlmChatClientResolver resolver = new LlmChatClientResolver(
                List.of(new StubChatClient(LlmProvider.OLLAMA, true),
                        new StubChatClient(LlmProvider.CLAUDE, true)),
                "claude"
        );

        assertThat(resolver.resolve(null).provider()).isEqualTo(LlmProvider.CLAUDE);
        assertThat(resolver.defaultProvider()).isEqualTo(LlmProvider.CLAUDE);
    }

    @Test
    void 지정한_제공자가_기본값보다_우선한다() {
        LlmChatClientResolver resolver = new LlmChatClientResolver(
                List.of(new StubChatClient(LlmProvider.OLLAMA, true),
                        new StubChatClient(LlmProvider.CLAUDE, true)),
                "ollama"
        );

        assertThat(resolver.resolve(LlmProvider.CLAUDE).provider()).isEqualTo(LlmProvider.CLAUDE);
    }

    @Test
    void 쓸_수_없는_제공자를_요청하면_실패한다() {
        // claude 구현은 떠 있지만 API 키가 없는 환경이다.
        LlmChatClientResolver resolver = new LlmChatClientResolver(
                List.of(new StubChatClient(LlmProvider.OLLAMA, true),
                        new StubChatClient(LlmProvider.CLAUDE, false)),
                "ollama"
        );

        assertThatThrownBy(() -> resolver.resolve(LlmProvider.CLAUDE))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).getCode())
                .isEqualTo(LlmErrorCode.PROVIDER_NOT_AVAILABLE);
    }

    @Test
    void 구현이_없는_제공자를_요청하면_실패한다() {
        LlmChatClientResolver resolver = new LlmChatClientResolver(
                List.of(new StubChatClient(LlmProvider.OLLAMA, true)),
                "ollama"
        );

        assertThatThrownBy(() -> resolver.resolve(LlmProvider.CLAUDE))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void 설정_기본값이_이상하면_기동_시점에_실패한다() {
        // 첫 run이 돌 때가 아니라 여기서 걸려야 배포 직후에 알 수 있다.
        assertThatThrownBy(() -> new LlmChatClientResolver(
                List.of(new StubChatClient(LlmProvider.OLLAMA, true)),
                "gpt"
        )).isInstanceOf(IllegalStateException.class);
    }

    private record StubChatClient(LlmProvider provider, boolean usable) implements LlmChatClient {

        @Override
        public String resolvePrimaryModel() {
            return "stub-" + provider;
        }

        @Override
        public boolean available() {
            return usable;
        }

        @Override
        public JsonNode call(String stepName, String systemPrompt, String userMessage, int maxTokens) {
            throw new UnsupportedOperationException("stub");
        }
    }
}
