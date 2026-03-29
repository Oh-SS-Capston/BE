package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.dto.facts.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.graphstore.dto.facts.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.dto.facts.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.dto.facts.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawEvidenceFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationResolutionDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationTableDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawSourceSpanDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawSymbolFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawSymbolTableDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GraphStoreFactsNormalizer {

    public NormalizedFactsDocument normalize(RawFactsDocumentDto raw) {
        if (raw == null) {
            return new NormalizedFactsDocument(null, Map.of(), List.of(), List.of());
        }

        return new NormalizedFactsDocument(
                raw.getSchemaVersion(),
                normalizeEvidence(raw.getEvidence()),
                normalizeSymbols(raw.getSymbols()),
                normalizeRelations(raw.getRelations())
        );
    }

    private Map<String, NormalizedEvidenceFact> normalizeEvidence(Map<String, RawEvidenceFactDto> rawEvidence) {
        Map<String, NormalizedEvidenceFact> result = new LinkedHashMap<>();
        if (rawEvidence == null || rawEvidence.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, RawEvidenceFactDto> entry : rawEvidence.entrySet()) {
            String evidenceId = entry.getKey();
            RawEvidenceFactDto dto = entry.getValue();
            if (dto == null) {
                continue;
            }

            RawSourceSpanDto span = dto.getSpan();

            result.put(evidenceId, new NormalizedEvidenceFact(
                    firstNonBlank(dto.getId(), evidenceId),
                    dto.getType(),
                    dto.getPath(),
                    span == null ? null : span.getStartLine(),
                    span == null ? null : span.getStartCol(),
                    span == null ? null : span.getEndLine(),
                    span == null ? null : span.getEndCol(),
                    dto.getSymbol(),
                    dto.getSnippet(),
                    dto.getHash()
            ));
        }

        return result;
    }

    private List<NormalizedSymbolFact> normalizeSymbols(RawSymbolTableDto table) {
        List<NormalizedSymbolFact> result = new ArrayList<>();
        if (table == null) {
            return result;
        }

        addSymbols(result, table.getModules());
        addSymbols(result, table.getPackages());
        addSymbols(result, table.getTypes());
        addSymbols(result, table.getConstructors());
        addSymbols(result, table.getMethods());
        addSymbols(result, table.getFields());

        return result;
    }

    private void addSymbols(List<NormalizedSymbolFact> target, List<RawSymbolFactDto> source) {
        if (source == null || source.isEmpty()) {
            return;
        }

        for (RawSymbolFactDto dto : source) {
            if (dto == null) {
                continue;
            }

            target.add(new NormalizedSymbolFact(
                    dto.getSymbol(),
                    dto.getName(),
                    dto.getKind(),
                    dto.getAccess(),
                    dto.getModifiers() == null ? List.of() : List.copyOf(dto.getModifiers()),
                    dto.getOrigin(),
                    dto.getQualifiedName(),
                    dto.getOwnerTypeSymbol(),
                    dto.getPackageSymbol(),
                    dto.getModule(),
                    dto.getSourceFile(),
                    dto.getEvidenceIds() == null ? List.of() : List.copyOf(dto.getEvidenceIds()),
                    dto.getSignature()
            ));
        }
    }

    private List<NormalizedRelationFact> normalizeRelations(RawRelationTableDto table) {
        List<NormalizedRelationFact> result = new ArrayList<>();
        if (table == null) {
            return result;
        }

        addRelations(result, table.getContains());
        addRelations(result, table.getExtendsRelations());
        addRelations(result, table.getImplementsRelations());
        addRelations(result, table.getOverrides());
        addRelations(result, table.getCalls());
        addRelations(result, table.getAccessesField());
        addRelations(result, table.getFieldType());
        addRelations(result, table.getParamType());
        addRelations(result, table.getReturnType());
        addRelations(result, table.getThrowsType());
        addRelations(result, table.getAnnotatedBy());

        return result;
    }

    private void addRelations(List<NormalizedRelationFact> target, List<RawRelationFactDto> source) {
        if (source == null || source.isEmpty()) {
            return;
        }

        for (RawRelationFactDto dto : source) {
            if (dto == null) {
                continue;
            }

            RawRelationResolutionDto resolution = dto.getResolution();

            target.add(new NormalizedRelationFact(
                    dto.getKind(),
                    dto.getSrcSymbol(),
                    dto.getDstSymbol(),
                    dto.getDstRawRef(),
                    dto.getOrigin(),
                    resolution == null ? null : resolution.getStatus(),
                    resolution == null ? null : resolution.getReason(),
                    dto.getConfidenceHint(),
                    dto.getEvidenceIds() == null ? List.of() : List.copyOf(dto.getEvidenceIds())
            ));
        }
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }
}