package com.example.ossdoc.global.llm.model;

import com.example.ossdoc.global.llm.dto.request.LlmRequest;

import java.util.List;
import java.util.Map;

/**
 * LLM 실행에 전달할 구조 시드와 근거 번들을 묶는 모델.
 */
public record LlmContextBundle(
        Map<String, Object> structureEngineOutput,
        List<LlmRequest.EvidenceSnippet> evidenceBundle
) {
}
