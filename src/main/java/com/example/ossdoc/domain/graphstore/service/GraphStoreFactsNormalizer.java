package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawEvidenceFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawObservationFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawObservationTableDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationResolutionDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationTableDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawSymbolFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawSymbolTableDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GraphStoreFactsNormalizer {

    private static final Set<String> PRIMITIVES_AND_VOID = Set.of(
            "void", "int", "long", "short", "byte", "char", "boolean", "float", "double",
            "?", "T", "E", "K", "V"
    );

    public NormalizedFactsDocument normalize(RawFactsDocumentDto raw) {
        if (raw == null) {
            return new NormalizedFactsDocument(null, Map.of(), List.of(), List.of(), List.of());
        }

        List<NormalizedSymbolFact> symbols = normalizeSymbols(raw.getSymbols());
        List<NormalizedRelationFact> relations = new ArrayList<>(normalizeRelations(raw.getRelations()));
        List<NormalizedSymbolFact> derivedSymbols = new ArrayList<>();

        deriveEdges(symbols, derivedSymbols, relations);

        List<NormalizedSymbolFact> allSymbols = new ArrayList<>(symbols);
        allSymbols.addAll(derivedSymbols);

        return new NormalizedFactsDocument(
                raw.getSchemaVersion(),
                normalizeEvidence(raw.getEvidence()),
                allSymbols,
                relations,
                normalizeObservations(raw.getObservations())
        );
    }

    private Map<String, NormalizedEvidenceFact> normalizeEvidence(List<RawEvidenceFactDto> rawEvidence) {
        Map<String, NormalizedEvidenceFact> result = new LinkedHashMap<>();
        if (rawEvidence == null || rawEvidence.isEmpty()) {
            return result;
        }

        for (RawEvidenceFactDto dto : rawEvidence) {
            if (dto == null || dto.getId() == null || dto.getId().isBlank()) {
                continue;
            }

            String evidenceId = dto.getId();
            result.put(evidenceId, new NormalizedEvidenceFact(
                    evidenceId,
                    dto.getType(),
                    dto.getPath(),
                    dto.getStartLine(),
                    dto.getStartCol(),
                    dto.getEndLine(),
                    dto.getEndCol(),
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

            JsonNode sig = dto.getSignature();
            target.add(new NormalizedSymbolFact(
                    dto.getSymbol(),
                    dto.getName(),
                    dto.getKind(),
                    dto.getTypeKind(),
                    dto.getSourceRoot(),
                    dto.getAccess(),
                    dto.getModifiers() == null ? List.of() : List.copyOf(dto.getModifiers()),
                    dto.getOrigin(),
                    dto.getQualifiedName(),
                    dto.getOwnerTypeSymbol(),
                    dto.getPackageSymbol(),
                    dto.getModule(),
                    dto.getSourceFile(),
                    dto.getEvidenceIds() == null ? List.of() : List.copyOf(dto.getEvidenceIds()),
                    sig,
                    extractTypeRefRaw(dto.getSuperTypeRef()),
                    extractTypeRefRaws(dto.getInterfaceTypeRefs()),
                    extractReturnTypeRef(sig),
                    extractParamTypeRefs(sig),
                    extractThrowsTypeRefs(sig),
                    extractFieldTypeRef(sig),
                    dto.getDocComment(),
                    dto.getAnnotations()
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

    // ── Phase 3: 심볼 필드 기반 파생 엣지 생성 ─────────────────────────────

    private void deriveEdges(List<NormalizedSymbolFact> symbols,
                             List<NormalizedSymbolFact> derivedSymbols,
                             List<NormalizedRelationFact> relations) {

        Set<String> existingSymbols = symbols.stream()
                .map(NormalizedSymbolFact::symbol)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, List<String>> packageToTypes = new LinkedHashMap<>();

        for (NormalizedSymbolFact sym : symbols) {
            if (sym.symbol() == null) continue;
            String kind = sym.kind() == null ? "" : sym.kind().toLowerCase();

            // 3-A: EXTENDS / IMPLEMENTS
            if ("type".equals(kind)) {
                if (sym.superclassTypeRef() != null && !sym.superclassTypeRef().isBlank()) {
                    relations.add(makeRelation("EXTENDS", sym.symbol(), sym.superclassTypeRef(), new BigDecimal("0.95")));
                }
                if (sym.interfaceTypeRefs() != null) {
                    for (String ref : sym.interfaceTypeRefs()) {
                        if (ref != null && !ref.isBlank()) {
                            relations.add(makeRelation("IMPLEMENTS", sym.symbol(), ref, new BigDecimal("0.95")));
                        }
                    }
                }
                // 3-C: package → type 매핑 수집
                if (sym.packageSymbol() != null && !sym.packageSymbol().isBlank()) {
                    packageToTypes.computeIfAbsent(sym.packageSymbol(), k -> new ArrayList<>()).add(sym.symbol());
                }
            }

            // 3-B: CONTAINS (ownerType → member)
            if (isMemberKind(kind) && sym.ownerTypeSymbol() != null && !sym.ownerTypeSymbol().isBlank()) {
                relations.add(makeRelation("CONTAINS", sym.ownerTypeSymbol(), sym.symbol(), BigDecimal.ONE));
            }

            // 3-D: HAS_FIELD (field symbol → field type)
            if ("field".equals(kind) && sym.fieldTypeRef() != null && !sym.fieldTypeRef().isBlank()) {
                relations.add(makeRelation("HAS_FIELD", sym.symbol(), sym.fieldTypeRef(), new BigDecimal("0.9")));
            }

            // 3-E: RETURNS / PARAM / THROWS
            if ("method".equals(kind) || "constructor".equals(kind)) {
                if (sym.returnTypeRef() != null && !sym.returnTypeRef().isBlank()
                        && !isPrimitiveOrVoid(sym.returnTypeRef())) {
                    relations.add(makeRelation("RETURNS", sym.symbol(), sym.returnTypeRef(), new BigDecimal("0.9")));
                }
                if (sym.paramTypeRefs() != null) {
                    for (String ref : sym.paramTypeRefs()) {
                        if (ref != null && !ref.isBlank() && !isPrimitiveOrVoid(ref)) {
                            relations.add(makeRelation("PARAM", sym.symbol(), ref, new BigDecimal("0.9")));
                        }
                    }
                }
                if (sym.throwsTypeRefs() != null) {
                    for (String ref : sym.throwsTypeRefs()) {
                        if (ref != null && !ref.isBlank()) {
                            relations.add(makeRelation("THROWS", sym.symbol(), ref, new BigDecimal("0.9")));
                        }
                    }
                }
            }
        }

        // 3-C: 패키지 가상 노드 추가 + CONTAINS(package → type)
        for (Map.Entry<String, List<String>> entry : packageToTypes.entrySet()) {
            String pkgSymbol = entry.getKey();
            if (!existingSymbols.contains(pkgSymbol)) {
                derivedSymbols.add(new NormalizedSymbolFact(
                        pkgSymbol, null, "package", null, null,
                        null, List.of(), "derived",
                        pkgSymbol, null, null, null, null,
                        List.of(), null,
                        null, List.of(), null, List.of(), List.of(), null,
                        null, null
                ));
                existingSymbols.add(pkgSymbol);
            }
            for (String typeSymbol : entry.getValue()) {
                relations.add(makeRelation("CONTAINS", pkgSymbol, typeSymbol, BigDecimal.ONE));
            }
        }
    }

    // ── TypeRef JsonNode 추출 헬퍼 ───────────────────────────────────────────

    private String extractTypeRefRaw(JsonNode typeRefNode) {
        if (typeRefNode == null || typeRefNode.isNull() || !typeRefNode.isObject()) return null;
        JsonNode rawNode = typeRefNode.get("raw");
        if (rawNode == null || rawNode.isNull() || !rawNode.isTextual()) return null;
        String raw = rawNode.asText().trim();
        return raw.isEmpty() ? null : raw;
    }

    private List<String> extractTypeRefRaws(List<JsonNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            String raw = extractTypeRefRaw(node);
            if (raw != null) result.add(raw);
        }
        return List.copyOf(result);
    }

    private String extractReturnTypeRef(JsonNode sig) {
        if (sig == null || sig.isNull()) return null;
        return extractTypeRefRaw(sig.get("returns"));
    }

    private List<String> extractParamTypeRefs(JsonNode sig) {
        if (sig == null || sig.isNull()) return List.of();
        JsonNode params = sig.get("params");
        if (params == null || !params.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode param : params) {
            String raw = extractTypeRefRaw(param.get("type_ref"));
            if (raw != null) result.add(raw);
        }
        return List.copyOf(result);
    }

    private List<String> extractThrowsTypeRefs(JsonNode sig) {
        if (sig == null || sig.isNull()) return List.of();
        JsonNode throwsArr = sig.get("throws");
        if (throwsArr == null || !throwsArr.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode t : throwsArr) {
            String raw = extractTypeRefRaw(t);
            if (raw != null) result.add(raw);
        }
        return List.copyOf(result);
    }

    private String extractFieldTypeRef(JsonNode sig) {
        if (sig == null || sig.isNull()) return null;
        return extractTypeRefRaw(sig.get("field_type"));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean isMemberKind(String kind) {
        return "method".equals(kind) || "field".equals(kind) || "constructor".equals(kind);
    }

    private boolean isPrimitiveOrVoid(String ref) {
        return PRIMITIVES_AND_VOID.contains(ref.trim());
    }

    private NormalizedRelationFact makeRelation(String kind, String src, String dst, BigDecimal confidence) {
        return new NormalizedRelationFact(kind, src, dst, null, "derived", null, null, confidence, List.of());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }

    private List<NormalizedObservationFact> normalizeObservations(RawObservationTableDto table) {
        List<NormalizedObservationFact> result = new ArrayList<>();
        if (table == null) {
            return result;
        }

        addObservations(result, table.getDiInjectionSites());
        addObservations(result, table.getDiProviders());
        addObservations(result, table.getSpiProviders());
        addObservations(result, table.getEventPublications());
        addObservations(result, table.getEventSubscriptions());
        addObservations(result, table.getReflectionSites());
        addObservations(result, table.getHttpEndpoints());
        addObservations(result, table.getScheduling());
        addObservations(result, table.getAsyncMethods());
        addObservations(result, table.getConfigWiring());
        addObservations(result, table.getReadmeMentions());
        addObservations(result, table.getModuleExports());
        addObservations(result, table.getModuleUses());
        addObservations(result, table.getModuleProvides());

        return result;
    }

    private void addObservations(List<NormalizedObservationFact> target, List<RawObservationFactDto> source) {
        if (source == null || source.isEmpty()) {
            return;
        }

        for (RawObservationFactDto dto : source) {
            if (dto == null) {
                continue;
            }
            if (dto.getKind() == null || dto.getKind().isBlank()) {
                continue;
            }
            if (dto.getSiteSymbol() == null || dto.getSiteSymbol().isBlank()) {
                continue;
            }

            BigDecimal confidenceHint = dto.getConfidenceHint() == null
                    ? null
                    : BigDecimal.valueOf(dto.getConfidenceHint());

            target.add(new NormalizedObservationFact(
                    dto.getKind(),
                    dto.getSiteSymbol(),
                    dto.getTargetSymbol(),
                    dto.getTargetTypeRef(),
                    dto.getNote(),
                    confidenceHint,
                    dto.getAttrs(),
                    dto.getEvidenceIds() == null ? List.of() : List.copyOf(dto.getEvidenceIds())
            ));
        }
    }
}
