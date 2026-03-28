package com.example.ossdoc.domain.extraction.service.writer;
import com.example.ossdoc.domain.extraction.dto.context.FactsWriteContext;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionMeta;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;
import com.example.ossdoc.domain.extraction.enums.BytecodeAvailability;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.service.support.util.FactsSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * writer 결과를 API 응답 dto로 변환한다.
 */
@Component
public class FactsResponseFactory {

    public FactsExtractResponse create(FactsWriteContext context) {
        FactsDocument document = context.document();
        ExtractionMeta extraction = document.extraction();

        return FactsExtractResponse.builder()
                .runId(context.runId())
                .mode(resolveMode(extraction))
                .bytecodeAvailability(resolveBytecodeAvailability(extraction))
                .schemaVersion(resolveSchemaVersion(document))
                .scannedModules(resolveScannedModules(extraction))
                .stats(resolveStats(document))
                .warnings(mergeWarnings(context.additionalWarnings(), extraction == null ? null : extraction.warnings()))
                .build();
    }

    private String resolveSchemaVersion(FactsDocument document) {
        String v = document == null ? null : document.schemaVersion();
        return (v == null || v.isBlank()) ? FactsSchema.SCHEMA_VERSION : v;
    }

    private ExtractionMode resolveMode(ExtractionMeta extraction) {
        return extraction == null ? null : extraction.mode();
    }

    private BytecodeAvailability resolveBytecodeAvailability(ExtractionMeta extraction) {
        return extraction == null ? null : extraction.bytecodeAvailability();
    }

    private List<String> resolveScannedModules(ExtractionMeta extraction) {
        if (extraction == null || extraction.scannedModules() == null) {
            return List.of();
        }
        return List.copyOf(extraction.scannedModules());
    }

    private StatsMeta resolveStats(FactsDocument document) {
        return document == null ? null : document.stats();
    }

    private List<String> mergeWarnings(List<String> additionalWarnings, List<String> extractionWarnings) {
        Set<String> merged = new LinkedHashSet<>();
        addWarnings(merged, additionalWarnings);
        addWarnings(merged, extractionWarnings);
        return new ArrayList<>(merged);
    }

    private void addWarnings(Set<String> target, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        for (String warning : warnings) {
            if (warning == null || warning.isBlank()) {
                continue;
            }
            target.add(warning);
        }
    }
}
