package com.example.ossdoc.domain.extraction.service.writer;

import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;

/**
 * facts.json writer 진입점
 *
 * 외부에서는 writer에게 "S3 업로드 + 응답 생성"까지 한 번에 맡긴다.
 */
public interface FactsWriter {

    FactsExtractResponse writeAndBuildResponse(FactsWriteContext context);
}