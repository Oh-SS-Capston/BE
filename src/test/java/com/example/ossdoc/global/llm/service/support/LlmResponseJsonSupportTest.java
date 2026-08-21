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
        // 값 안의 [ 와 { 를 깊이로 세면 닫는 구분자가 3개가 되어 복원이 깨진다.
        // 완결된 문자열 뒤에서 잘린 입력을 쓴다 — 그래야 미완성 쌍 폐기와 무관하게
        // "괄호를 깊이로 세는가"만 검증된다.
        String truncated = "{\"message\":\"배열 [ 과 객체 { 를 설명\",\"next\":\"잘린";

        LlmResponseJsonSupport.TruncationRepair repair =
                LlmResponseJsonSupport.repairTruncatedJson(truncated);
        JsonNode recovered = LlmResponseJsonSupport.tryRecoverTruncatedJson(objectMapper, truncated);

        assertThat(repair.closersAppended()).isEqualTo(1);
        assertThat(recovered).isNotNull();
        assertThat(recovered.path("message").asText()).isEqualTo("배열 [ 과 객체 { 를 설명");
        // 잘린 뒤쪽 쌍은 버려진다.
        assertThat(recovered.has("next")).isFalse();
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

        // 계약이 바뀌었다. 예전에는 열린 문자열에 닫는 따옴표를 붙여 마무리했고
        // "복원은 항목을 버리지 않으므로 결과가 원본보다 짧아질 수 없다"가 단언이었다.
        // 그러면 잘린 조각("미완성")이 문법적으로 완전한 값이 되어 품질 게이트가
        // 채운 칸으로 센다. 이제는 미완성 쌍을 버리므로 결과가 짧아진다.
        assertThat(repair.json().length()).isLessThan(truncated.length());
        assertThat(repair.unterminatedValueDroppedChars()).isGreaterThan(0);
        assertThat(repair.json()).doesNotContain("미완성");
    }

    @Test
    @DisplayName("문자열 한가운데서 끊기면 그 key/value 쌍을 버린다 (조각을 완성된 값으로 위장하지 않는다)")
    void dropsUnterminatedPairInsteadOfClosingTheQuote() throws Exception {
        // run B의 SCN-006이 실제로 이렇게 끝났다.
        String truncated = "{\"steps\":[{\"action\":\"getSaturatePredicate() 호출\","
                + "\"confidenceReason\":\"filePath 와 start";

        LlmResponseJsonSupport.TruncationRepair repair =
                LlmResponseJsonSupport.repairTruncatedJson(truncated);
        JsonNode node = objectMapper.readTree(repair.json());
        JsonNode step = node.path("steps").get(0);

        // 앞서 완결된 값은 그대로 남는다.
        assertThat(step.path("action").asText()).isEqualTo("getSaturatePredicate() 호출");
        // 잘린 칸은 조각이 아니라 부재다 — 게이트가 미충족으로 세게 된다.
        assertThat(step.has("confidenceReason")).isFalse();
        assertThat(repair.unterminatedValueDroppedChars()).isGreaterThan(0);
    }

    @Test
    @DisplayName("배열 원소 한가운데서 끊기면 그 원소만 사라지고 앞 원소는 남는다")
    void dropsOnlyTheUnfinishedArrayElement() throws Exception {
        String truncated = "{\"scenarios\":[{\"id\":\"SCN-001\"},{\"id\":\"SCN-0";

        JsonNode node = objectMapper.readTree(
                LlmResponseJsonSupport.repairTruncatedJson(truncated).json());
        JsonNode scenarios = node.path("scenarios");

        assertThat(scenarios.get(0).path("id").asText()).isEqualTo("SCN-001");
        // 두 번째 원소는 껍데기만 남고 잘린 값은 실리지 않는다.
        assertThat(scenarios.get(1).has("id")).isFalse();
    }

    @Test
    @DisplayName("정상 종료한 응답에서는 아무것도 버리지 않는다")
    void dropsNothingWhenResponseIsComplete() {
        LlmResponseJsonSupport.TruncationRepair repair =
                LlmResponseJsonSupport.repairTruncatedJson("{\"a\":\"x\",\"b\":\"y\"}");

        assertThat(repair.unterminatedString()).isFalse();
        assertThat(repair.unterminatedValueDroppedChars()).isZero();
        assertThat(repair.json()).isEqualTo("{\"a\":\"x\",\"b\":\"y\"}");
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

    /**
     * 출력 상한은 골격 step 수에 비례해야 한다.
     *
     * <p>고정 1600은 4 step까지 우연히 충분했고(run B 실측 최대 1129) 6 step에서 깨졌다.
     * run B 실측 적합은 {@code y = 297·steps - 91}이므로 6 step에 1,693이 필요한데
     * 1600으로는 모자란다. 새 산식은 2,500을 준다.</p>
     */
    @Test
    @DisplayName("시나리오 출력 상한은 step 수에 비례하고 6 step에서 실측 필요량을 넘는다")
    void scenarioTokenBudgetScalesWithStepCount() {
        var properties = new com.example.ossdoc.global.llm.config.LlmGenerationProperties();

        assertThat(properties.tokensForScenario(2)).isEqualTo(1100);
        assertThat(properties.tokensForScenario(3)).isEqualTo(1450);
        assertThat(properties.tokensForScenario(4)).isEqualTo(1800);
        assertThat(properties.tokensForScenario(6)).isEqualTo(2500);

        // run B 실측(step, 출력 토큰)을 전부 덮는지. 6 step은 절단돼 적합값을 쓴다.
        assertThat(properties.tokensForScenario(2)).isGreaterThan(585);
        assertThat(properties.tokensForScenario(3)).isGreaterThan(922);
        assertThat(properties.tokensForScenario(4)).isGreaterThan(1129);
        assertThat(properties.tokensForScenario(6)).isGreaterThan(1693);

        // step이 0이어도 음수 상한이 나오지 않는다.
        assertThat(properties.tokensForScenario(0)).isPositive();
        assertThat(properties.tokensForScenario(-1)).isPositive();
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
