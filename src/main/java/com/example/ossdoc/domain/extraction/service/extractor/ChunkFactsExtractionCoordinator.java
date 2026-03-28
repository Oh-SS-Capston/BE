package com.example.ossdoc.domain.extraction.service.extractor;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;

import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ChunkFactsExtractionCoordinator {

    private final List<FactsExtractor> extractors;
    private final ExtractionContextFactory extractionContextFactory;

    public ChunkResult extract(
            FactsExtractRequest request,
            ExtractionPreflightResult preflightResult,
            Path repoRoot,
            ChunkDescriptor chunk
    ) {
        ExtractionContext context = extractionContextFactory.create(request, preflightResult, repoRoot, chunk);
        return extract(context);
    }

    public ChunkResult extract(ExtractionContext context) {
        FactsExtractor extractor = extractorFor(context.chunkKind());
        return extractor.extract(context);
    }

    private FactsExtractor extractorFor(ChunkKind kind) {
        return extractors.stream()
                .filter(extractor -> extractor.supports() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No extractor registered for chunk kind: " + kind));
    }
}
