package com.example.ossdoc.domain.extraction.dto.context;

import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * writer 입력 컨텍스트
 *
 * writer는 composer가 완성한 FactsDocument를 받아 S3에 업로드하고
 * 추가 warning을 extraction warning과 합쳐 최종 응답을 만든다.
 */
public record FactsWriteContext(
        String runId,
        Path artifactsRoot,
        FactsDocument document,
        List<String> additionalWarnings
) {
    public FactsWriteContext {
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(artifactsRoot, "artifactsRoot must not be null");
        Objects.requireNonNull(document, "document must not be null");
        additionalWarnings = additionalWarnings == null ? List.of() : List.copyOf(additionalWarnings);
    }
}
