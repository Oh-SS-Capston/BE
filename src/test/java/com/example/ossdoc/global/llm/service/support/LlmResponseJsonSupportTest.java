package com.example.ossdoc.global.llm.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmResponseJsonSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("코드펜스로 감싼 응답에서 본문만 꺼낸다")
    void stripsMarkdownFence() {
        String fenced = "```json\n{\"a\":1}\n```";

        assertThat(LlmResponseJsonSupport.stripFence(fenced)).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("펜스가 없으면 원문을 그대로 둔다")
    void keepsPlainTextUnchanged() {
        assertThat(LlmResponseJsonSupport.stripFence("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("추론 블록을 제거하고 뒤따르는 본문만 남긴다")
    void stripsThinkBlock() {
        String withThink = "<think>고민 중</think>\n{\"a\":1}";

        assertThat(LlmResponseJsonSupport.stripThinkBlock(withThink)).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("닫히지 않은 추론 블록은 빈 문자열로 처리해 파싱 실패로 넘긴다")
    void treatsUnclosedThinkBlockAsEmpty() {
        assertThat(LlmResponseJsonSupport.stripThinkBlock("<think>끝나지 않은 추론")).isEmpty();
    }

    @Test
    @DisplayName("잘린 배열/객체를 닫아 파싱 가능한 JSON으로 복원한다")
    void recoversTruncatedJson() {
        String truncated = "{\"cautions\":[{\"cautionId\":\"CAU-001\",\"title\":\"미완성";

        JsonNode recovered = LlmResponseJsonSupport.tryRecoverTruncatedJson(objectMapper, truncated);

        assertThat(recovered).isNotNull();
        assertThat(recovered.path("cautions").get(0).path("cautionId").asText()).isEqualTo("CAU-001");
    }

    @Test
    @DisplayName("문자열 안의 괄호는 중첩 깊이로 세지 않는다")
    void ignoresBracketsInsideStrings() {
        String truncated = "{\"message\":\"배열 [ 과 객체 { 를 설명";

        JsonNode recovered = LlmResponseJsonSupport.tryRecoverTruncatedJson(objectMapper, truncated);

        assertThat(recovered).isNotNull();
        assertThat(recovered.path("message").asText()).isEqualTo("배열 [ 과 객체 { 를 설명");
    }

    @Test
    @DisplayName("JSON이 전혀 없으면 복원하지 않고 null을 반환한다")
    void returnsNullWhenNoJsonPresent() {
        assertThat(LlmResponseJsonSupport.tryRecoverTruncatedJson(objectMapper, "설명만 있는 응답")).isNull();
    }

    @Test
    @DisplayName("복원 내역은 덧붙인 닫는 구분자 수를 센다 (길이 차이로 재지 않는다)")
    void reportsWhatRepairActuallyDid() {
        // 객체 > 배열 > 객체 로 세 겹이 열린 채 문자열 한가운데서 끊긴 응답.
        String truncated = "{\"cautions\":[{\"cautionId\":\"CAU-001\",\"title\":\"미완성";

        LlmResponseJsonSupport.TruncationRepair repair =
                LlmResponseJsonSupport.repairTruncatedJson(truncated);

        assertThat(repair.closersAppended()).isEqualTo(3);
        assertThat(repair.unterminatedString()).isTrue();
        assertThat(repair.preambleDroppedChars()).isZero();
        assertThat(repair.repaired()).isTrue();
        // 복원은 항목을 버리지 않고 닫으므로 결과가 원본보다 짧아질 수 없다.
        // 예전 "폐기 N자" 지표가 음수를 찍던 원인이다.
        assertThat(repair.json().length()).isGreaterThanOrEqualTo(truncated.length());
    }

    @Test
    @DisplayName("JSON 앞에 붙은 서두는 폐기 길이로 센다")
    void countsPreambleAsDropped() {
        LlmResponseJsonSupport.TruncationRepair repair =
                LlmResponseJsonSupport.repairTruncatedJson("여기 결과입니다: {\"a\":1}");

        assertThat(repair.preambleDroppedChars()).isEqualTo("여기 결과입니다: ".length());
        assertThat(repair.closersAppended()).isZero();
        assertThat(repair.json()).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("정상 종료한 JSON은 복원 흔적이 없다")
    void reportsNoRepairForCompleteJson() {
        LlmResponseJsonSupport.TruncationRepair repair =
                LlmResponseJsonSupport.repairTruncatedJson("{\"a\":1}");

        assertThat(repair.repaired()).isFalse();
    }

    @Test
    @DisplayName("404는 재시도 대상이 아니고 429/5xx는 재시도 대상이다")
    void classifiesRetryableStatuses() {
        assertThat(LlmResponseJsonSupport.isRetryableStatus(404)).isFalse();
        assertThat(LlmResponseJsonSupport.isRetryableStatus(400)).isFalse();
        assertThat(LlmResponseJsonSupport.isRetryableStatus(429)).isTrue();
        assertThat(LlmResponseJsonSupport.isRetryableStatus(503)).isTrue();
    }

    @Test
    @DisplayName("백오프는 지수적으로 늘어나되 상한을 넘지 않는다")
    void backsOffExponentiallyUpToCap() {
        assertThat(LlmResponseJsonSupport.backoffDelayMillis(1)).isEqualTo(1500L);
        assertThat(LlmResponseJsonSupport.backoffDelayMillis(2)).isEqualTo(3000L);
        assertThat(LlmResponseJsonSupport.backoffDelayMillis(10)).isEqualTo(12000L);
    }
}
