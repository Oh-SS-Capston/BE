package com.example.ossdoc.domain.rule.dto.json;

import lombok.Builder;

import java.util.List;

@Builder
/*
 * RuleCandidatesJson:
 * - 기존 candidates(규칙 후보 목록)와 함께 methodCards(메서드 기능 카드)를 함께 제공한다.
 * - 프론트/LLM이 "규칙 후보 중심 보기"와 "메서드 기능 설명 보기"를 동시에 구성할 수 있게 한다.
 */
public record RuleCandidatesJson(
        String schemaVersion,
        String runId,
        String generatedAt,
        RuleCandidateSummaryJson summary,
        RuleCandidateDisplayPolicyJson displayPolicy,
        List<RuleMethodCardJson> methodCards,
        List<RuleCandidateItem> candidates
) {
}
