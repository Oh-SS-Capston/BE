package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawEvidenceFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawObservationFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawObservationTableDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationResolutionDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawRelationTableDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawSymbolFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawSymbolTableDto;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class GraphStoreFactsNormalizer {

    // 바이트코드 내부 클래스 구분자 $UpperCase를 .UpperCase로 정규화한다.
    // 익명 클래스($1, $2)는 숫자로 시작하므로 매칭에서 제외된다.
    private static final Pattern INNER_CLASS_DOLLAR =
            Pattern.compile("\\$([A-Z])");

    private static final Set<String> PRIMITIVES_AND_VOID = Set.of(
            "void", "int", "long", "short", "byte", "char", "boolean",
            "float", "double", "?", "T", "E", "K", "V"
    );

    public NormalizedFactsDocument normalize(RawFactsDocumentDto raw) {
        if (raw == null) {
            return new NormalizedFactsDocument(
                    null,
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        List<NormalizedSymbolFact> symbols =
                normalizeSymbols(raw.getSymbols());

        List<NormalizedRelationFact> relations =
                new ArrayList<>(normalizeRelations(raw.getRelations()));

        List<NormalizedSymbolFact> derivedSymbols =
                new ArrayList<>();

        deriveEdges(symbols, derivedSymbols, relations);

        List<NormalizedSymbolFact> allSymbols =
                new ArrayList<>(symbols);

        allSymbols.addAll(derivedSymbols);

        return new NormalizedFactsDocument(
                raw.getSchemaVersion(),
                normalizeEvidence(raw.getEvidence()),
                allSymbols,
                relations,
                normalizeObservations(raw.getObservations())
        );
    }

    private Map<String, NormalizedEvidenceFact> normalizeEvidence(
            List<RawEvidenceFactDto> rawEvidence
    ) {
        Map<String, NormalizedEvidenceFact> result =
                new LinkedHashMap<>();

        if (rawEvidence == null || rawEvidence.isEmpty()) {
            return result;
        }

        for (RawEvidenceFactDto dto : rawEvidence) {
            if (dto == null
                    || dto.getId() == null
                    || dto.getId().isBlank()) {
                continue;
            }

            String evidenceId = dto.getId();

            result.put(
                    evidenceId,
                    new NormalizedEvidenceFact(
                            evidenceId,
                            dto.getType(),
                            dto.getPath(),
                            dto.getStartLine(),
                            dto.getStartCol(),
                            dto.getEndLine(),
                            dto.getEndCol(),
                            dto.getSymbol(),
                            dto.getSnippet(),
                            dto.getHash(),
                            dto.getAttrs()
                    )
            );
        }

        return result;
    }

    private List<NormalizedSymbolFact> normalizeSymbols(
            RawSymbolTableDto table
    ) {
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

    private void addSymbols(
            List<NormalizedSymbolFact> target,
            List<RawSymbolFactDto> source
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }

        for (RawSymbolFactDto dto : source) {
            if (dto == null) {
                continue;
            }

            /*
             * 바이트코드 inner class 구분자($UpperCase)를
             * AST 표기(.UpperCase)로 통일한다.
             */
            String normalizedSymbol =
                    normalizeInnerClassSeparator(dto.getSymbol());

            String normalizedOwner =
                    normalizeInnerClassSeparator(dto.getOwnerTypeSymbol());

            JsonNode signature = dto.getSignature();

            target.add(new NormalizedSymbolFact(
                    normalizedSymbol,
                    dto.getName(),
                    dto.getKind(),
                    dto.getTypeKind(),
                    dto.getSourceRoot(),
                    dto.getAccess(),
                    dto.getModifiers() == null
                            ? List.of()
                            : List.copyOf(dto.getModifiers()),
                    dto.getOrigin(),
                    dto.getQualifiedName(),
                    normalizedOwner,
                    dto.getPackageSymbol(),
                    dto.getModule(),
                    dto.getSourceFile(),
                    dto.getEvidenceIds() == null
                            ? List.of()
                            : List.copyOf(dto.getEvidenceIds()),
                    signature,
                    extractTypeRefRaw(dto.getSuperTypeRef()),
                    extractTypeRefRaws(dto.getInterfaceTypeRefs()),
                    extractReturnTypeRef(signature),
                    extractParamTypeRefs(signature),
                    extractThrowsTypeRefs(signature),
                    extractFieldTypeRef(signature),
                    dto.getDocComment(),
                    dto.getAnnotations()
            ));
        }
    }

    private List<NormalizedRelationFact> normalizeRelations(
            RawRelationTableDto table
    ) {
        List<NormalizedRelationFact> result = new ArrayList<>();

        if (table == null) {
            return result;
        }

        addRelations(result, table.getContains());
        addRelations(result, table.getExtendsRelations());
        addRelations(result, table.getImplementsRelations());
        addRelations(result, table.getOverrides());
        addRelations(result, table.getCalls());
        addRelations(result, table.getCreates());
        addRelations(result, table.getAccessesField());
        addRelations(result, table.getFieldType());
        addRelations(result, table.getParamType());
        addRelations(result, table.getReturnType());
        addRelations(result, table.getThrowsType());
        addRelations(result, table.getAnnotatedBy());

        List<RawRelationFactDto> annotatedWith =
                table.getAnnotatedWith();

        addRelations(result, annotatedWith);

        if (annotatedWith == null || annotatedWith.isEmpty()) {
            addRelations(result, table.getAnnotatedBy());
        }

        return result;
    }

    /**
     * Raw relation 정보를 정규화 모델로 손실 없이 전달한다.
     */
    private void addRelations(
            List<NormalizedRelationFact> target,
            List<RawRelationFactDto> source
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }

        for (RawRelationFactDto dto : source) {
            if (dto == null) {
                continue;
            }

            RawRelationResolutionDto resolution =
                    dto.getResolution();

            target.add(new NormalizedRelationFact(
                    dto.getKind(),
                    normalizeInnerClassSeparator(dto.getSrcSymbol()),
                    normalizeInnerClassSeparator(dto.getDstSymbol()),
                    dto.getDstRawRef(),
                    dto.getOrigin(),
                    dto.getDerivation(),
                    resolution == null
                            ? null
                            : resolution.getStatus(),
                    resolution == null
                            ? null
                            : resolution.getReason(),
                    dto.getCallSiteLine(),
                    dto.getConfidenceHint(),
                    dto.getAttrs(),
                    dto.getEvidenceIds() == null
                            ? List.of()
                            : List.copyOf(dto.getEvidenceIds())
            ));
        }
    }

    // ── Phase 3: 심볼 필드 기반 파생 엣지 생성 ─────────────────────────────

    private void deriveEdges(
            List<NormalizedSymbolFact> symbols,
            List<NormalizedSymbolFact> derivedSymbols,
            List<NormalizedRelationFact> relations
    ) {
        Set<String> existingSymbols = symbols.stream()
                .map(NormalizedSymbolFact::symbol)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        /*
         * package → type 관계를 만들 때 type의 origin을 보존하기 위해
         * symbol ID만 저장하지 않고 NormalizedSymbolFact를 저장한다.
         */
        Map<String, List<NormalizedSymbolFact>> packageToTypes =
                new LinkedHashMap<>();

        for (NormalizedSymbolFact symbol : symbols) {
            if (symbol.symbol() == null) {
                continue;
            }

            String kind = symbol.kind() == null
                    ? ""
                    : symbol.kind().toLowerCase();

            // 3-A: EXTENDS / IMPLEMENTS
            if ("type".equals(kind)) {
                if (symbol.superclassTypeRef() != null
                        && !symbol.superclassTypeRef().isBlank()) {

                    addDerivedTypeRelation(
                            relations,
                            "EXTENDS",
                            symbol.symbol(),
                            symbol.superclassTypeRef(),
                            symbol.origin(),
                            new BigDecimal("0.95")
                    );
                }

                if (symbol.interfaceTypeRefs() != null) {
                    for (String reference : symbol.interfaceTypeRefs()) {
                        if (reference == null
                                || reference.isBlank()) {
                            continue;
                        }

                        addDerivedTypeRelation(
                                relations,
                                "IMPLEMENTS",
                                symbol.symbol(),
                                reference,
                                symbol.origin(),
                                new BigDecimal("0.95")
                        );
                    }
                }

                if (symbol.packageSymbol() != null
                        && !symbol.packageSymbol().isBlank()) {

                    packageToTypes
                            .computeIfAbsent(
                                    symbol.packageSymbol(),
                                    ignored -> new ArrayList<>()
                            )
                            .add(symbol);
                }
            }

            // 3-B: CONTAINS (owner type → member)
            if (isMemberKind(kind)
                    && symbol.ownerTypeSymbol() != null
                    && !symbol.ownerTypeSymbol().isBlank()) {

                relations.add(
                        makeDerivedRelation(
                                "CONTAINS",
                                symbol.ownerTypeSymbol(),
                                symbol.symbol(),
                                symbol.origin(),
                                BigDecimal.ONE
                        )
                );
            }

            // 3-D: HAS_FIELD (field symbol → field type)
            if ("field".equals(kind)
                    && symbol.fieldTypeRef() != null
                    && !symbol.fieldTypeRef().isBlank()) {

                addDerivedTypeRelation(
                        relations,
                        "HAS_FIELD",
                        symbol.symbol(),
                        symbol.fieldTypeRef(),
                        symbol.origin(),
                        new BigDecimal("0.9")
                );
            }

            // 3-E: RETURNS / PARAM / THROWS
            if ("method".equals(kind)
                    || "constructor".equals(kind)) {

                if (symbol.returnTypeRef() != null
                        && !symbol.returnTypeRef().isBlank()
                        && !isPrimitiveOrVoid(
                        symbol.returnTypeRef()
                )) {
                    addDerivedTypeRelation(
                            relations,
                            "RETURNS",
                            symbol.symbol(),
                            symbol.returnTypeRef(),
                            symbol.origin(),
                            new BigDecimal("0.9")
                    );
                }

                if (symbol.paramTypeRefs() != null) {
                    for (String reference :
                            symbol.paramTypeRefs()) {

                        if (reference == null
                                || reference.isBlank()
                                || isPrimitiveOrVoid(reference)) {
                            continue;
                        }

                        addDerivedTypeRelation(
                                relations,
                                "PARAM",
                                symbol.symbol(),
                                reference,
                                symbol.origin(),
                                new BigDecimal("0.9")
                        );
                    }
                }

                if (symbol.throwsTypeRefs() != null) {
                    for (String reference :
                            symbol.throwsTypeRefs()) {

                        if (reference == null
                                || reference.isBlank()) {
                            continue;
                        }

                        addDerivedTypeRelation(
                                relations,
                                "THROWS",
                                symbol.symbol(),
                                reference,
                                symbol.origin(),
                                new BigDecimal("0.9")
                        );
                    }
                }
            }
        }

        // 3-C: 패키지 가상 노드 추가 + CONTAINS(package → type)
        for (Map.Entry<String, List<NormalizedSymbolFact>> entry
                : packageToTypes.entrySet()) {

            String packageSymbol = entry.getKey();

            if (!existingSymbols.contains(packageSymbol)) {
                derivedSymbols.add(
                        new NormalizedSymbolFact(
                                packageSymbol,
                                null,
                                "package",
                                null,
                                null,
                                null,
                                List.of(),
                                "derived",
                                packageSymbol,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                null,
                                null,
                                List.of(),
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null,
                                null
                        )
                );

                existingSymbols.add(packageSymbol);
            }

            for (NormalizedSymbolFact typeSymbol :
                    entry.getValue()) {

                relations.add(
                        makeDerivedRelation(
                                "CONTAINS",
                                packageSymbol,
                                typeSymbol.symbol(),
                                typeSymbol.origin(),
                                BigDecimal.ONE
                        )
                );
            }
        }
    }
    // ── TypeRef JsonNode 추출 헬퍼 ───────────────────────────────────────────

    private String extractTypeRefRaw(JsonNode typeRefNode) {
        if (typeRefNode == null
                || typeRefNode.isNull()
                || !typeRefNode.isObject()) {
            return null;
        }

        JsonNode rawNode = typeRefNode.get("raw");

        if (rawNode == null
                || rawNode.isNull()
                || !rawNode.isTextual()) {
            return null;
        }

        String raw = rawNode.asText().trim();

        return raw.isEmpty() ? null : raw;
    }

    private List<String> extractTypeRefRaws(List<JsonNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        for (JsonNode node : nodes) {
            String raw = extractTypeRefRaw(node);

            if (raw != null) {
                result.add(raw);
            }
        }

        return List.copyOf(result);
    }

    private String extractReturnTypeRef(JsonNode signature) {
        if (signature == null || signature.isNull()) {
            return null;
        }

        return extractTypeRefRaw(signature.get("returns"));
    }

    private List<String> extractParamTypeRefs(JsonNode signature) {
        if (signature == null || signature.isNull()) {
            return List.of();
        }

        JsonNode params = signature.get("params");

        if (params == null || !params.isArray()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        for (JsonNode param : params) {
            String raw = extractTypeRefRaw(param.get("type_ref"));

            if (raw != null) {
                result.add(raw);
            }
        }

        return List.copyOf(result);
    }

    private List<String> extractThrowsTypeRefs(JsonNode signature) {
        if (signature == null || signature.isNull()) {
            return List.of();
        }

        JsonNode throwsArray = signature.get("throws");

        if (throwsArray == null || !throwsArray.isArray()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        for (JsonNode thrownType : throwsArray) {
            String raw = extractTypeRefRaw(thrownType);

            if (raw != null) {
                result.add(raw);
            }
        }

        return List.copyOf(result);
    }

    private String extractFieldTypeRef(JsonNode signature) {
        if (signature == null || signature.isNull()) {
            return null;
        }

        return extractTypeRefRaw(signature.get("field_type"));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean isMemberKind(String kind) {
        return "method".equals(kind)
                || "field".equals(kind)
                || "constructor".equals(kind);
    }

    private boolean isPrimitiveOrVoid(String reference) {
        return PRIMITIVES_AND_VOID.contains(reference.trim());
    }

    private void addDerivedTypeRelation(
            List<NormalizedRelationFact> relations,
            String kind,
            String sourceSymbol,
            String typeReference,
            String origin,
            BigDecimal confidence
    ) {
        String destinationSymbol =
                toTypeSymbolId(typeReference);

        if (destinationSymbol == null) {
            return;
        }

        relations.add(
                makeDerivedRelation(
                        kind,
                        sourceSymbol,
                        destinationSymbol,
                        origin,
                        confidence
                )
        );
    }

    private String toTypeSymbolId(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }

        String normalized =
                normalizeInnerClassSeparator(
                        reference.trim()
                );

        boolean alreadyPrefixed =
                normalized.startsWith("type:");

        String rawTypeName =
                alreadyPrefixed
                        ? normalized.substring("type:".length())
                        : normalized;

        if (rawTypeName.isBlank()
                || isPrimitiveOrVoid(rawTypeName)) {
            return null;
        }

        return alreadyPrefixed
                ? normalized
                : "type:" + normalized;
    }

    /**
     * SymbolFact의 구조 정보를 이용해 생성한 relation.
     *
     * origin은 근거가 된 symbol의 수집 출처를 relation 도메인으로 변환해 사용하고,
     * derivation은 derived로 별도 기록한다.
     *
     * symbol에서 파생되는 모든 relation(EXTENDS/IMPLEMENTS/CONTAINS/HAS_FIELD/
     * RETURNS/PARAM/THROWS)은 이 메서드를 거치므로, 변환을 여기 한 곳에 둔다.
     */
    private NormalizedRelationFact makeDerivedRelation(
            String kind,
            String sourceSymbol,
            String destinationSymbol,
            String symbolOrigin,
            BigDecimal confidence
    ) {
        return new NormalizedRelationFact(
                kind,
                sourceSymbol,
                destinationSymbol,
                null,
                toRelationOrigin(symbolOrigin),
                "derived",
                null,
                null,
                null,
                confidence,
                Map.of(),
                List.of()
        );
    }

    /**
     * symbol의 origin 값을 relation의 origin 값으로 변환한다.
     *
     * [왜 변환이 필요한가]
     * 두 값 집합은 서로 다른 enum이다.
     *   symbol   : SymbolOriginKind  = ast, bytecode, generated, ast_and_bytecode
     *   relation : FactsEdgeConverter.toOriginKind가 받는 집합
     *              = ast, bytecode, merged(ast_and_bytecode), contract, derived, observed, resource
     * 대부분 이름이 겹쳐 그냥 넘겨도 통했지만, generated는 relation 쪽에 대응값이 없다.
     * 그대로 넘기면 edge 변환 단계에서 다음으로 터진다.
     *   IllegalArgumentException: Unsupported relation origin: generated
     * 실제로 record를 쓰는 저장소(JUnit)에서 record 컴포넌트 필드 105개가
     * origin=generated로 수집되어 HAS_FIELD/CONTAINS 파생 중 GRAPHSTORE 전체가 죽었다.
     *
     * [generated를 derived로 보내는 이유]
     * 여기서 만드는 relation은 이미 derivation="derived"인 파생 관계다.
     * "컴파일러가 생성한 심볼"이라는 정보는 symbol 쪽 origin에 그대로 남아 있으므로
     * relation 쪽에서 잃는 정보가 없다.
     *
     * 모르는 값은 임의로 통과시키지 않고 derived로 모은다.
     * relation origin 도메인에 없는 값이 edge 변환까지 흘러가 파이프라인을 죽이는 것보다,
     * 파생 관계라는 사실만 남기는 편이 안전하다.
     */
    private String toRelationOrigin(String symbolOrigin) {
        if (symbolOrigin == null || symbolOrigin.isBlank()) {
            return "derived";
        }

        return switch (symbolOrigin.trim().toLowerCase(Locale.ROOT)) {
            case "ast" -> "ast";
            case "bytecode" -> "bytecode";
            case "ast_and_bytecode" -> "ast_and_bytecode";
            default -> "derived";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    private String normalizeInnerClassSeparator(String symbolId) {
        if (symbolId == null || !symbolId.contains("$")) {
            return symbolId;
        }

        return INNER_CLASS_DOLLAR
                .matcher(symbolId)
                .replaceAll(".$1");
    }

    private List<NormalizedObservationFact> normalizeObservations(
            RawObservationTableDto table
    ) {
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

    private void addObservations(
            List<NormalizedObservationFact> target,
            List<RawObservationFactDto> source
    ) {
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

            if (dto.getSiteSymbol() == null
                    || dto.getSiteSymbol().isBlank()) {
                continue;
            }

            BigDecimal confidenceHint =
                    dto.getConfidenceHint() == null
                            ? null
                            : BigDecimal.valueOf(
                            dto.getConfidenceHint()
                    );

            target.add(new NormalizedObservationFact(
                    dto.getKind(),
                    dto.getSiteSymbol(),
                    dto.getTargetSymbol(),
                    dto.getTargetTypeRef(),
                    dto.getNote(),
                    dto.getOrigin(),
                    confidenceHint,
                    dto.getAttrs(),
                    dto.getEvidenceIds() == null
                            ? List.of()
                            : List.copyOf(dto.getEvidenceIds())
            ));
        }
    }
}

