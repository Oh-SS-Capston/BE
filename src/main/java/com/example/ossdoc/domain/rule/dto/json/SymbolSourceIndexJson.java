package com.example.ossdoc.domain.rule.dto.json;

import lombok.Builder;

import java.util.List;

@Builder
/*
 * SymbolSourceIndexJson:
 * - symbol_id를 키로 코드 위치/근거 연결 정보를 제공하는 브릿지 산출물.
 * - LLM 입력 조립에서 파일/라인 앵커 보강용으로 사용한다.
 */
public record SymbolSourceIndexJson(
        String schemaVersion,
        String runId,
        String generatedAt,
        Integer symbolCount,
        List<SymbolSourceIndexItemJson> symbols
) {
}
