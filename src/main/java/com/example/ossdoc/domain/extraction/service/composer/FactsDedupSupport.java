package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ParamFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationResolution;
import com.example.ossdoc.domain.extraction.dto.model.SignatureFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * AST/ASM에서 중복으로 들어올 수 있는 fact를
 * composer 단계에서 병합하기 위한 유틸.
 */
final class FactsDedupSupport {

    private FactsDedupSupport() {
    }

    static EvidenceFact mergeEvidence(EvidenceFact left, EvidenceFact right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return EvidenceFact.builder()
                .id(firstNonBlank(left.id(), right.id()))
                .type(firstNonNull(left.type(), right.type()))
                .path(firstNonBlank(left.path(), right.path()))
                .startLine(firstNonNull(left.startLine(), right.startLine()))
                .endLine(firstNonNull(left.endLine(), right.endLine()))
                .startCol(firstNonNull(left.startCol(), right.startCol()))
                .endCol(firstNonNull(left.endCol(), right.endCol()))
                .symbol(firstNonBlank(left.symbol(), right.symbol()))
                .snippet(preferLonger(left.snippet(), right.snippet()))
                .hash(firstNonBlank(left.hash(), right.hash()))
                .attrs(mergeMaps(left.attrs(), right.attrs()))
                .build();
    }

    static SymbolFact mergeSymbol(SymbolFact left, SymbolFact right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return SymbolFact.builder()
                .symbol(firstNonBlank(left.symbol(), right.symbol()))
                .kind(firstNonNull(left.kind(), right.kind()))
                .typeKind(firstNonNull(left.typeKind(), right.typeKind()))
                .name(firstNonBlank(left.name(), right.name()))
                .qualifiedName(firstNonBlank(left.qualifiedName(), right.qualifiedName()))
                .ownerSymbol(firstNonBlank(left.ownerSymbol(), right.ownerSymbol()))
                .packageSymbol(firstNonBlank(left.packageSymbol(), right.packageSymbol()))
                .module(firstNonBlank(left.module(), right.module()))
                .sourceRoot(firstNonBlank(left.sourceRoot(), right.sourceRoot()))
                .bytecodeRoot(firstNonBlank(left.bytecodeRoot(), right.bytecodeRoot()))
                .nestedIn(firstNonBlank(left.nestedIn(), right.nestedIn()))
                .access(firstNonNull(left.access(), right.access()))
                .modifiers(mergeSets(left.modifiers(), right.modifiers()))
                .origin(firstNonNull(left.origin(), right.origin()))
                .annotations(mergeDistinct(
                        left.annotations(),
                        right.annotations(),
                        FactsDedupSupport::typeRefKey
                ))
                .evidenceIds(mergeDistinct(
                        left.evidenceIds(),
                        right.evidenceIds(),
                        Function.identity()
                ))
                .attrs(mergeMaps(left.attrs(), right.attrs()))
                .signature(mergeSignature(left.signature(), right.signature()))
                .superTypeRef(mergeTypeRef(
                        left.superTypeRef(),
                        right.superTypeRef()
                ))
                .interfaceTypeRefs(mergeDistinct(
                        left.interfaceTypeRefs(),
                        right.interfaceTypeRefs(),
                        FactsDedupSupport::typeRefKey
                ))
                .sourceFile(firstNonBlank(left.sourceFile(), right.sourceFile()))
                .docComment(firstNonBlank(left.docComment(), right.docComment()))
                .typeParams(firstNonNull(left.typeParams(), right.typeParams()))
                .testCoverageHint(firstNonNull(
                        left.testCoverageHint(),
                        right.testCoverageHint()
                ))
                .throwsUnchecked(firstNonNull(
                        left.throwsUnchecked(),
                        right.throwsUnchecked()
                ))
                .hasConditionalThrow(firstNonNull(
                        left.hasConditionalThrow(),
                        right.hasConditionalThrow()
                ))
                .stateMutations(firstNonNull(
                        left.stateMutations(),
                        right.stateMutations()
                ))
                .build();
    }

    /**
     * 동일 relation에서 확인된 정보를 하나로 병합한다.
     *
     * 주요 정책:
     * - evidence ID는 중복 없이 합친다.
     * - resolution은 더 높은 해석 상태를 우선한다.
     * - AST와 BYTECODE 출처가 함께 확인되면 AST_AND_BYTECODE로 승격한다.
     * - derivation은 직접 확인된 관계를 우선한다.
     * - confidence는 더 높은 값을 사용한다.
     */
    static RelationFact mergeRelation(RelationFact left, RelationFact right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return RelationFact.builder()
                .kind(firstNonNull(left.kind(), right.kind()))
                .srcSymbol(firstNonBlank(
                        left.srcSymbol(),
                        right.srcSymbol()
                ))
                .dstSymbol(firstNonBlank(
                        left.dstSymbol(),
                        right.dstSymbol()
                ))
                .dstRawRef(firstNonBlank(
                        left.dstRawRef(),
                        right.dstRawRef()
                ))
                .evidenceIds(mergeDistinct(
                        left.evidenceIds(),
                        right.evidenceIds(),
                        Function.identity()
                ))
                .resolution(mergeResolution(
                        left.resolution(),
                        right.resolution()
                ))
                .origin(mergeOrigin(
                        left.origin(),
                        right.origin()
                ))
                .derivation(mergeDerivation(
                        left.derivation(),
                        right.derivation()
                ))
                .callSiteLine(firstNonNull(
                        left.callSiteLine(),
                        right.callSiteLine()
                ))
                .confidenceHint(max(
                        left.confidenceHint(),
                        right.confidenceHint()
                ))
                .attrs(mergeMaps(
                        left.attrs(),
                        right.attrs()
                ))
                .build();
    }

    static ObservationFact mergeObservation(
            ObservationFact left,
            ObservationFact right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return ObservationFact.builder()
                .kind(firstNonNull(left.kind(), right.kind()))
                .siteSymbol(firstNonBlank(
                        left.siteSymbol(),
                        right.siteSymbol()
                ))
                .targetSymbol(firstNonBlank(
                        left.targetSymbol(),
                        right.targetSymbol()
                ))
                .targetTypeRef(mergeTypeRef(
                        left.targetTypeRef(),
                        right.targetTypeRef()
                ))
                .note(preferLonger(left.note(), right.note()))
                .evidenceIds(mergeDistinct(
                        left.evidenceIds(),
                        right.evidenceIds(),
                        Function.identity()
                ))
                .origin(mergeOrigin(
                        left.origin(),
                        right.origin()
                ))
                .confidenceHint(max(
                        left.confidenceHint(),
                        right.confidenceHint()
                ))
                .attrs(mergeMaps(left.attrs(), right.attrs()))
                .build();
    }

    static RelationResolution mergeResolution(
            RelationResolution left,
            RelationResolution right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        int leftRank = resolutionRank(left.status());
        int rightRank = resolutionRank(right.status());

        RelationResolution winner = leftRank >= rightRank ? left : right;
        RelationResolution loser = leftRank >= rightRank ? right : left;

        return RelationResolution.builder()
                .status(firstNonNull(
                        winner.status(),
                        loser.status()
                ))
                .reason(firstNonBlank(
                        winner.reason(),
                        loser.reason()
                ))
                .build();
    }

    static SignatureFact mergeSignature(
            SignatureFact left,
            SignatureFact right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return SignatureFact.builder()
                .params(mergeDistinct(
                        left.params(),
                        right.params(),
                        FactsDedupSupport::paramFactKey
                ))
                .returns(mergeTypeRef(
                        left.returns(),
                        right.returns()
                ))
                .throwsTypes(mergeDistinct(
                        left.throwsTypes(),
                        right.throwsTypes(),
                        FactsDedupSupport::typeRefKey
                ))
                .fieldType(mergeTypeRef(
                        left.fieldType(),
                        right.fieldType()
                ))
                .javadoc(firstNonBlank(
                        left.javadoc(),
                        right.javadoc()
                ))
                .sealed(firstNonNull(
                        left.sealed(),
                        right.sealed()
                ))
                .build();
    }

    static TypeRef mergeTypeRef(TypeRef left, TypeRef right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return TypeRef.builder()
                .raw(firstNonBlank(left.raw(), right.raw()))
                .args(mergeDistinct(
                        left.args(),
                        right.args(),
                        FactsDedupSupport::typeRefKey
                ))
                .arrayDim(firstNonNull(
                        left.arrayDim(),
                        right.arrayDim()
                ))
                .primitive(firstNonNull(
                        left.primitive(),
                        right.primitive()
                ))
                .unresolved(firstNonNull(
                        left.unresolved(),
                        right.unresolved()
                ))
                .sourceText(firstNonBlank(
                        left.sourceText(),
                        right.sourceText()
                ))
                .wildcard(firstNonNull(
                        left.wildcard(),
                        right.wildcard()
                ))
                .build();
    }

    static <T> List<T> mergeDistinct(
            List<T> left,
            List<T> right,
            Function<T, String> keyFn
    ) {
        LinkedHashMap<String, T> merged = new LinkedHashMap<>();
        addDistinct(merged, left, keyFn);
        addDistinct(merged, right, keyFn);
        return List.copyOf(merged.values());
    }

    static <T> Set<T> mergeSets(Set<T> left, Set<T> right) {
        LinkedHashSet<T> merged = new LinkedHashSet<>();

        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }

        if (merged.isEmpty()) {
            return Set.of();
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<>(merged)
        );
    }

    static <K, V> Map<K, V> mergeMaps(
            Map<K, V> left,
            Map<K, V> right
    ) {
        LinkedHashMap<K, V> merged = new LinkedHashMap<>();

        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }

        if (merged.isEmpty()) {
            return Map.of();
        }

        return Collections.unmodifiableMap(
                new LinkedHashMap<>(merged)
        );
    }

    /**
     * relation의 논리적 동일성을 판단하기 위한 키.
     *
     * origin과 derivation은 동일 관계의 출처 및 생성 방식이므로
     * relation identity에는 포함하지 않는다.
     */
    static String relationKey(RelationFact relation) {
        return String.join("|",
                safe(relation.kind() == null
                        ? null
                        : relation.kind().code()),
                safe(relation.srcSymbol()),
                safe(relation.dstSymbol()),
                safe(relation.dstRawRef())
        );
    }

    static String observationKey(ObservationFact observation) {
        return String.join("|",
                safe(observation.kind() == null
                        ? null
                        : observation.kind().code()),
                safe(observation.siteSymbol()),
                safe(observation.targetSymbol()),
                semanticTypeRefKey(observation.targetTypeRef()),
                observationDiscriminator(observation)
        );
    }

    private static String observationDiscriminator(
            ObservationFact observation
    ) {
        if (observation == null
                || observation.kind() != ObservationKind.REFLECTION_SITE
                || observation.attrs() == null) {
            return "";
        }

        return String.join("~",
                attrValue(observation.attrs(), "reflection_kind"),
                attrValue(observation.attrs(), "api_method", "method"),
                attrValue(observation.attrs(), "target_type"),
                attrValue(observation.attrs(), "member_name"),
                attrValue(observation.attrs(), "scope"),
                attrValue(observation.attrs(), "descriptor")
        );
    }

    private static String attrValue(
            Map<String, Object> attrs,
            String... keys
    ) {
        if (attrs == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    /**
     * observation의 논리적 동일성 판정용 타입 키.
     * sourceText와 unresolved 여부는 추출기별 부가정보이므로 key에서 제외한다.
     */
    static String semanticTypeRefKey(TypeRef typeRef) {
        if (typeRef == null) {
            return "";
        }

        List<String> argKeys = new ArrayList<>();
        if (typeRef.args() != null) {
            for (TypeRef arg : typeRef.args()) {
                argKeys.add(semanticTypeRefKey(arg));
            }
        }

        return String.join("~",
                safe(typeRef.raw()),
                String.join(",", argKeys),
                safe(typeRef.arrayDim() == null
                        ? null
                        : String.valueOf(typeRef.arrayDim())),
                safe(typeRef.wildcard() == null
                        ? null
                        : typeRef.wildcard().code())
        );
    }

    static String evidenceKey(EvidenceFact evidenceFact) {
        return safe(evidenceFact.id());
    }

    static String symbolKey(SymbolFact symbolFact) {
        if (symbolFact.symbol() != null
                && !symbolFact.symbol().isBlank()) {
            return symbolFact.symbol();
        }

        return String.join("|",
                safe(symbolFact.kind() == null
                        ? null
                        : symbolFact.kind().code()),
                safe(symbolFact.qualifiedName()),
                safe(symbolFact.ownerSymbol()),
                safe(symbolFact.name())
        );
    }

    static String typeRefKey(TypeRef typeRef) {
        if (typeRef == null) {
            return "";
        }

        List<String> argKeys = new ArrayList<>();
        if (typeRef.args() != null) {
            for (TypeRef arg : typeRef.args()) {
                argKeys.add(typeRefKey(arg));
            }
        }

        return String.join("|",
                safe(typeRef.raw()),
                String.join(",", argKeys),
                safe(typeRef.arrayDim() == null
                        ? null
                        : String.valueOf(typeRef.arrayDim())),
                safe(typeRef.primitive() == null
                        ? null
                        : String.valueOf(typeRef.primitive())),
                safe(typeRef.unresolved() == null
                        ? null
                        : String.valueOf(typeRef.unresolved())),
                safe(typeRef.sourceText()),
                safe(typeRef.wildcard() == null
                        ? null
                        : typeRef.wildcard().code())
        );
    }

    static String paramFactKey(ParamFact paramFact) {
        if (paramFact == null) {
            return "";
        }

        return String.join("|",
                safe(paramFact.name()),
                typeRefKey(paramFact.typeRef())
        );
    }

    static String preferLonger(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }

        return right.length() > left.length() ? right : left;
    }

    static <T> T firstNonNull(T left, T right) {
        return left != null ? left : right;
    }

    static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }

        return right != null && !right.isBlank()
                ? right
                : null;
    }

    static Double max(Double left, Double right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return Math.max(left, right);
    }

    /**
     * relation의 수집 출처를 병합한다.
     *
     * AST와 BYTECODE에서 동일 관계가 발견되면
     * AST_AND_BYTECODE로 승격한다.
     */
    private static FactOriginKind mergeOrigin(
            FactOriginKind left,
            FactOriginKind right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        if (left == right) {
            return left;
        }

        if (isAstAndBytecodeCombination(left, right)) {
            return FactOriginKind.AST_AND_BYTECODE;
        }

        /*
         * RESOURCE, OBSERVED 등 서로 다른 종류의 출처를
         * 하나의 enum 값으로 정확히 표현할 수 없는 경우에는
         * 기존 우선순위를 변경하지 않고 먼저 병합된 값을 유지한다.
         */
        return left;
    }

    private static boolean isAstAndBytecodeCombination(
            FactOriginKind left,
            FactOriginKind right
    ) {
        if (left == FactOriginKind.AST_AND_BYTECODE) {
            return right == FactOriginKind.AST
                    || right == FactOriginKind.BYTECODE
                    || right == FactOriginKind.AST_AND_BYTECODE;
        }

        if (right == FactOriginKind.AST_AND_BYTECODE) {
            return left == FactOriginKind.AST
                    || left == FactOriginKind.BYTECODE
                    || left == FactOriginKind.AST_AND_BYTECODE;
        }

        return (left == FactOriginKind.AST
                && right == FactOriginKind.BYTECODE)
                || (left == FactOriginKind.BYTECODE
                && right == FactOriginKind.AST);
    }

    /**
     * 같은 관계가 여러 방식으로 생성된 경우
     * 더 확정적인 생성 방식을 유지한다.
     */
    private static DerivationKind mergeDerivation(
            DerivationKind left,
            DerivationKind right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        return derivationRank(left) >= derivationRank(right)
                ? left
                : right;
    }

    private static int derivationRank(DerivationKind derivation) {
        if (derivation == null) {
            return -1;
        }

        return switch (derivation) {
            case DIRECT -> 4;
            case DERIVED -> 3;
            case INFERRED -> 2;
            case HEURISTIC -> 1;
        };
    }

    private static int resolutionRank(ResolutionStatus status) {
        if (status == null) {
            return -1;
        }

        return switch (status) {
            case RESOLVED -> 3;
            case PARTIAL -> 2;
            case UNRESOLVED -> 1;
        };
    }

    private static <T> void addDistinct(
            Map<String, T> target,
            Collection<T> values,
            Function<T, String> keyFn
    ) {
        if (values == null || values.isEmpty()) {
            return;
        }

        for (T value : values) {
            if (value == null) {
                continue;
            }

            String key = safe(keyFn.apply(value));
            if (!target.containsKey(key)) {
                target.put(key, value);
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}


