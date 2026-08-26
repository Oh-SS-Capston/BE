package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.enums.LlmProvider;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/*
 * run이 지정한 제공자에 맞는 LlmChatClient를 고른다.
 *
 * 왜 필요한가:
 * - 이전에는 @ConditionalOnProperty로 구현 하나만 빈에 올려서, 기동 후에는 제공자를 바꿀 수 없었다.
 *   run 단위로 고르려면 두 구현이 모두 떠 있어야 하고, 고르는 책임을 둘 자리가 필요하다.
 *
 * 기본값 정책:
 * - run에 지정이 없으면(=기존 run, 또는 프론트가 안 보낸 요청) ossdoc.llm.provider 설정값을 쓴다.
 *   덕분에 이 기능이 붙기 전에 만들어진 run도 그대로 동작한다.
 * - 설정값 자체가 이상하면 기동 시점이 아니라 첫 호출에서 알게 되므로, 생성자에서 한 번 검증한다.
 */
@Slf4j
@Component
public class LlmChatClientResolver {

    private final Map<LlmProvider, LlmChatClient> clientsByProvider = new EnumMap<>(LlmProvider.class);
    private final LlmProvider defaultProvider;

    public LlmChatClientResolver(
            List<LlmChatClient> clients,
            @Value("${ossdoc.llm.provider:ollama}") String configuredDefaultProvider
    ) {
        for (LlmChatClient client : clients) {
            clientsByProvider.put(client.provider(), client);
        }

        LlmProvider parsed = LlmProvider.from(configuredDefaultProvider);
        if (parsed == null) {
            throw new IllegalStateException(
                    "ossdoc.llm.provider 값을 인식할 수 없습니다: " + configuredDefaultProvider
            );
        }
        this.defaultProvider = parsed;

        log.info(
                "[LlmProvider] resolver ready. default={}, registered={}",
                defaultProvider,
                clientsByProvider.keySet()
        );
    }

    /**
     * 제공자를 확정해 클라이언트를 돌려준다.
     *
     * @param requested run이 지정한 제공자. null이면 설정 기본값을 쓴다.
     * @throws LlmException 구현이 없거나 지금 환경에서 쓸 수 없는 제공자인 경우
     */
    public LlmChatClient resolve(LlmProvider requested) {
        LlmProvider target = requested == null ? defaultProvider : requested;

        LlmChatClient client = clientsByProvider.get(target);
        if (client == null) {
            log.warn("[LlmProvider] 구현이 없는 제공자 요청. requested={}", target);
            throw new LlmException(LlmErrorCode.PROVIDER_NOT_AVAILABLE);
        }

        if (!client.available()) {
            /*
             * 대표적으로 claude를 요청했는데 CLAUDE_API_KEY가 없는 경우다.
             * 여기서 막지 않으면 40분짜리 단계 중간에 401로 죽는다.
             */
            log.warn("[LlmProvider] 사용할 수 없는 제공자 요청. requested={}", target);
            throw new LlmException(LlmErrorCode.PROVIDER_NOT_AVAILABLE);
        }

        return client;
    }

    /** run에 지정이 없을 때 쓰이는 제공자. 저장 시점에 확정하기 위해 노출한다. */
    public LlmProvider defaultProvider() {
        return defaultProvider;
    }
}
