package com.example.ossdoc.domain.extraction.facade;

import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;

/**
 * extraction 전체 흐름 진입점
 */
public interface FactsExtractionFacade {

    FactsExtractResponse extract(FactsExtractRequest request);
}
