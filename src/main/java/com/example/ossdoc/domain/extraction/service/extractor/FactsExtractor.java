package com.example.ossdoc.domain.extraction.service.extractor;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;

import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;

/**
 * AST / BYTECODE extractor 공통 인터페이스.
 *
 * 새 구조에서는 "전체 extraction context"가 아니라
 * planner가 만든 단일 chunk를 받아 ChunkResult를 반환한다.
 */
public interface FactsExtractor {

    ChunkKind supports();

    ChunkResult extract(ExtractionContext context);
}
