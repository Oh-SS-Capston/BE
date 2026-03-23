package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;

/**
 * composer 직전 최종 집계 결과를 facts.json 루트 문서로 조립하는 계약.
 */
public interface FactsComposer {

    FactsDocument compose(FactsCompositionContext context);
}
