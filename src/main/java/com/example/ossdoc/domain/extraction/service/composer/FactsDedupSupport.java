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
import com.example.ossdoc.domain.extraction.service.support.evidence.EvidenceMergePolicy;

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

    static EvidenceFact mergeEvidence(
            EvidenceFact left,
            EvidenceFact right
    ) {
        return EvidenceMergePolicy.merge(left, right);
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
                .qualifiedName(firstNonBlank(
                        left.qualifiedName(),
                        right.qualifiedName()
                ))
                .ownerSymbol(firstNonBlank(
                        left.ownerSymbol(),
                        right.ownerSymbol()
                ))
                .packageSymbol(firstNonBlank(
                        left.packageSymbol(),
                        right.packageSymbol()
                ))
                .module(firstNonBlank(
                        left.module(),
                        right.module()
                ))
                .sourceRoot(firstNonBlank(
                        left.sourceRoot(),
                        right.sourceRoot()
                ))
                .bytecodeRoot(firstNonBlank(
                        left.bytecodeRoot(),
                        right.bytecodeRoot()
                ))
                .nestedIn(firstNonBlank(
                        left.nestedIn(),
                        right.nestedIn()
                ))
                .access(firstNonNull(
                        left.access(),
                        right.access()
                ))
                .modifiers(mergeSets(
                        left.modifiers(),
                        right.modifiers()
                ))
                .origin(firstNonNull(
                        left.origin(),
                        right.origin()
                ))
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
                .attrs(mergeMaps(
                        left.attrs(),
                        right.attrs()
                ))
                .signature(mergeSignature(
                        left.signature(),
                        right.signature()
                ))
                .superTypeRef(mergeTypeRef(
                        left.superTypeRef(),
                        right.superTypeRef()
                ))
                .interfaceTypeRefs(mergeDistinct(
                        left.interfaceTypeRefs(),
                        right.interfaceTypeRefs(),
                        FactsDedupSupport::typeRefKey
                ))
                .sourceFile(firstNonBlank(
                        left.sourceFile(),
                        right.sourceFile()
                ))
                .docComment(firstNonBlank(
                        left.docComment(),
                        right.docComment()
                ))
                .typeParams(firstNonNull(
                        left.typeParams(),
                        right.typeParams()
                ))
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
    static RelationFact mergeRelation(
            RelationFact left,
            RelationFact right
    ) {
        if (left == null) {
            return right;
        }

        if (right == null) {
            return left;
        }

        return RelationFact.builder()
                .kind(firstNonNull(
                        left.kind(),
                        right.kind()
                ))
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

    /**
     * 동일 observation에서 확인된 정보를 하나로 병합한다.
     */
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
                .kind(firstNonNull(
                        left.kind(),
                        right.kind()
                ))
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
                .note(preferLonger(
                        left.note(),
                        right.note()
                ))
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
                .attrs(mergeMaps(
                        left.attrs(),
                        right.attrs()
                ))
                .build();
    }

    /**
     * 동일 relation의 resolution 정보를 병합한다.
     *
     * RESOLVED > PARTIAL > UNRESOLVED 순으로
     * 더 확정적인 상태를 우선한다.
     */
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

        int leftRank =
                resolutionRank(left.status());

        int rightRank =
                resolutionRank(right.status());

        RelationResolution winner =
                leftRank >= rightRank
                        ? left
                        : right;

        RelationResolution loser =
                leftRank >= rightRank
                        ? right
                        : left;

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

    static TypeRef mergeTypeRef(
            TypeRef left,
            TypeRef right
    ) {
        if (left == null) {
            return right;
        }

        if (right == null) {
            return left;
        }

        return TypeRef.builder()
                .raw(firstNonBlank(
                        left.raw(),
                        right.raw()
                ))
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

    /**
     * 두 List를 key 기준으로 중복 제거하면서 병합한다.
     *
     * 먼저 등장한 값을 유지하고 입력 순서를 보존한다.
     */
    static <T> List<T> mergeDistinct(
            List<T> left,
            List<T> right,
            Function<T, String> keyFn
    ) {
        LinkedHashMap<String, T> merged =
                new LinkedHashMap<>();

        addDistinct(
                merged,
                left,
                keyFn
        );

        addDistinct(
                merged,
                right,
                keyFn
        );

        return List.copyOf(
                merged.values()
        );
    }

    /**
     * 두 Set을 병합한다.
     */
    static <T> Set<T> mergeSets(
            Set<T> left,
            Set<T> right
    ) {
        LinkedHashSet<T> merged =
                new LinkedHashSet<>();

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

    /**
     * 두 Map을 병합한다.
     *
     * 동일 key가 존재하면 right의 값을 최종값으로 사용한다.
     */
    static <K, V> Map<K, V> mergeMaps(
            Map<K, V> left,
            Map<K, V> right
    ) {
        LinkedHashMap<K, V> merged =
                new LinkedHashMap<>();

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
     * relation의 논리적 동일성을 판단하기 위한 key.
     *
     * origin과 derivation은 동일 관계의 출처 및 생성 방식이므로
     * relation identity에는 포함하지 않는다.
     */
    static String relationKey(
            RelationFact relation
    ) {
        return String.join(
                "|",
                safe(
                        relation.kind() == null
                                ? null
                                : relation.kind().code()
                ),
                safe(relation.srcSymbol()),
                safe(relation.dstSymbol()),
                safe(relation.dstRawRef())
        );
    }

    /**
     * observation의 논리적 동일성을 판단하는 key.
     *
     * 일반 observation은 다음 정보를 사용한다.
     *
     * - observation kind
     * - siteSymbol
     * - targetSymbol
     * - targetTypeRef
     *
     * Reflection observation은 추가로 reflection 관련 attrs를 포함한다.
     * 같은 메서드에서 여러 reflection 호출이 발생했을 때
     * 서로 다른 호출이 하나로 합쳐지는 문제를 방지하기 위한 처리다.
     */
    static String observationKey(
            ObservationFact observation
    ) {
        if (observation == null) {
            return "";
        }

        String baseKey = String.join(
                "|",
                safe(
                        observation.kind() == null
                                ? null
                                : observation.kind().code()
                ),
                safe(observation.siteSymbol()),
                safe(observation.targetSymbol()),
                semanticTypeRefKey(
                        observation.targetTypeRef()
                )
        );

        /*
         * 기존 문제:
         *
         * 같은 메서드 안에 다음 두 reflection 호출이 있다고 가정한다.
         *
         * sample.First.first()
         * sample.Second.second()
         *
         * targetSymbol과 targetTypeRef가 모두 비어 있으면
         * 기존 observationKey는 두 호출 모두 동일한 key를 생성했다.
         *
         * 그 결과 실제로는 서로 다른 reflection 호출인데도
         * 하나의 observation으로 병합될 수 있었다.
         *
         * 따라서 Reflection observation에 한해
         * 실제 호출 대상을 구분할 수 있는 attrs를 key에 포함한다.
         */
        if (observation.kind()
                == ObservationKind.REFLECTION_SITE) {

            Map<String, Object> attrs =
                    observation.attrs();

            /*
             * attrs가 없는 경우에는 기존 base key를 사용한다.
             */
            if (attrs == null
                    || attrs.isEmpty()) {

                return baseKey;
            }

            return String.join(
                    "|",
                    baseKey,

                    /*
                     * method / field / constructor / type 등
                     * reflection 대상 종류.
                     */
                    safeValue(
                            attrs.get(
                                    "reflection_kind"
                            )
                    ),

                    /*
                     * getDeclaredMethod 등
                     * 실제 reflection API 메서드.
                     */
                    safeValue(
                            attrs.get(
                                    "api_method"
                            )
                    ),

                    /*
                     * reflection 대상 타입.
                     *
                     * 예:
                     * sample.First
                     * sample.Second
                     */
                    safeValue(
                            attrs.get(
                                    "target_type"
                            )
                    ),

                    /*
                     * reflection 대상 멤버명.
                     *
                     * 예:
                     * first
                     * second
                     */
                    safeValue(
                            attrs.get(
                                    "member_name"
                            )
                    )
            );
        }

        return baseKey;
    }

    /**
     * observation의 논리적 동일성 판정용 타입 key.
     *
     * sourceText와 unresolved 여부는 추출기별 부가정보이므로
     * observation identity에서는 제외한다.
     */
    static String semanticTypeRefKey(
            TypeRef typeRef
    ) {
        if (typeRef == null) {
            return "";
        }

        List<String> argKeys =
                new ArrayList<>();

        if (typeRef.args() != null) {
            for (TypeRef arg : typeRef.args()) {
                argKeys.add(
                        semanticTypeRefKey(arg)
                );
            }
        }

        return String.join(
                "~",
                safe(typeRef.raw()),
                String.join(
                        ",",
                        argKeys
                ),
                safe(
                        typeRef.arrayDim() == null
                                ? null
                                : String.valueOf(
                                typeRef.arrayDim()
                        )
                ),
                safe(
                        typeRef.wildcard() == null
                                ? null
                                : typeRef
                                .wildcard()
                                .code()
                )
        );
    }

    /**
     * EvidenceFact의 중복 제거 key.
     */
    static String evidenceKey(
            EvidenceFact evidenceFact
    ) {
        return safe(
                evidenceFact.id()
        );
    }

    /**
     * SymbolFact의 중복 제거 key.
     *
     * symbol 값이 존재하면 symbol 자체를 사용하고,
     * 없으면 kind / qualifiedName / owner / name을 조합한다.
     */
    static String symbolKey(
            SymbolFact symbolFact
    ) {
        if (symbolFact.symbol() != null
                && !symbolFact
                .symbol()
                .isBlank()) {

            return symbolFact.symbol();
        }

        return String.join(
                "|",
                safe(
                        symbolFact.kind() == null
                                ? null
                                : symbolFact.kind().code()
                ),
                safe(
                        symbolFact.qualifiedName()
                ),
                safe(
                        symbolFact.ownerSymbol()
                ),
                safe(
                        symbolFact.name()
                )
        );
    }

    /**
     * TypeRef의 완전한 중복 제거 key.
     *
     * semanticTypeRefKey와 달리 추출기별 세부 정보까지 포함한다.
     */
    static String typeRefKey(
            TypeRef typeRef
    ) {
        if (typeRef == null) {
            return "";
        }

        List<String> argKeys =
                new ArrayList<>();

        if (typeRef.args() != null) {
            for (TypeRef arg : typeRef.args()) {
                argKeys.add(
                        typeRefKey(arg)
                );
            }
        }

        return String.join(
                "|",
                safe(
                        typeRef.raw()
                ),
                String.join(
                        ",",
                        argKeys
                ),
                safe(
                        typeRef.arrayDim() == null
                                ? null
                                : String.valueOf(
                                typeRef.arrayDim()
                        )
                ),
                safe(
                        typeRef.primitive() == null
                                ? null
                                : String.valueOf(
                                typeRef.primitive()
                        )
                ),
                safe(
                        typeRef.unresolved() == null
                                ? null
                                : String.valueOf(
                                typeRef.unresolved()
                        )
                ),
                safe(
                        typeRef.sourceText()
                ),
                safe(
                        typeRef.wildcard() == null
                                ? null
                                : typeRef
                                .wildcard()
                                .code()
                )
        );
    }

    /**
     * ParamFact의 중복 제거 key.
     */
    static String paramFactKey(
            ParamFact paramFact
    ) {
        if (paramFact == null) {
            return "";
        }

        return String.join(
                "|",
                safe(
                        paramFact.name()
                ),
                typeRefKey(
                        paramFact.typeRef()
                )
        );
    }

    /**
     * 두 문자열 중 더 긴 값을 선택한다.
     *
     * note 등의 정보가 AST와 ASM에서 각각 추출된 경우
     * 상대적으로 더 많은 정보를 가진 문자열을 유지하기 위한 정책이다.
     */
    static String preferLonger(
            String left,
            String right
    ) {
        if (left == null
                || left.isBlank()) {

            return right;
        }

        if (right == null
                || right.isBlank()) {

            return left;
        }

        return right.length() > left.length()
                ? right
                : left;
    }

    /**
     * null이 아닌 첫 번째 값을 반환한다.
     */
    static <T> T firstNonNull(
            T left,
            T right
    ) {
        return left != null
                ? left
                : right;
    }

    /**
     * 비어 있지 않은 첫 번째 문자열을 반환한다.
     */
    static String firstNonBlank(
            String left,
            String right
    ) {
        if (left != null
                && !left.isBlank()) {

            return left;
        }

        return right != null
                && !right.isBlank()
                ? right
                : null;
    }

    /**
     * 두 Double 중 더 큰 값을 반환한다.
     */
    static Double max(
            Double left,
            Double right
    ) {
        if (left == null) {
            return right;
        }

        if (right == null) {
            return left;
        }

        return Math.max(
                left,
                right
        );
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

        if (isAstAndBytecodeCombination(
                left,
                right
        )) {
            return FactOriginKind.AST_AND_BYTECODE;
        }

        /*
         * RESOURCE, OBSERVED 등 서로 다른 종류의 출처를
         * 하나의 enum 값으로 정확히 표현할 수 없는 경우에는
         * 기존 우선순위를 변경하지 않고 먼저 병합된 값을 유지한다.
         */
        return left;
    }

    /**
     * AST와 BYTECODE 조합인지 확인한다.
     */
    private static boolean isAstAndBytecodeCombination(
            FactOriginKind left,
            FactOriginKind right
    ) {
        if (left
                == FactOriginKind.AST_AND_BYTECODE) {

            return right == FactOriginKind.AST
                    || right == FactOriginKind.BYTECODE
                    || right == FactOriginKind.AST_AND_BYTECODE;
        }

        if (right
                == FactOriginKind.AST_AND_BYTECODE) {

            return left == FactOriginKind.AST
                    || left == FactOriginKind.BYTECODE
                    || left == FactOriginKind.AST_AND_BYTECODE;
        }

        return (
                left == FactOriginKind.AST
                        && right == FactOriginKind.BYTECODE
        ) || (
                left == FactOriginKind.BYTECODE
                        && right == FactOriginKind.AST
        );
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

        return derivationRank(left)
                >= derivationRank(right)
                ? left
                : right;
    }

    /**
     * DerivationKind 우선순위.
     *
     * DIRECT > DERIVED > INFERRED > HEURISTIC
     */
    private static int derivationRank(
            DerivationKind derivation
    ) {
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

    /**
     * ResolutionStatus 우선순위.
     *
     * RESOLVED > PARTIAL > UNRESOLVED
     */
    private static int resolutionRank(
            ResolutionStatus status
    ) {
        if (status == null) {
            return -1;
        }

        return switch (status) {
            case RESOLVED -> 3;
            case PARTIAL -> 2;
            case UNRESOLVED -> 1;
        };
    }

    /**
     * Collection을 target Map에 key 기준으로 중복 없이 추가한다.
     */
    private static <T> void addDistinct(
            Map<String, T> target,
            Collection<T> values,
            Function<T, String> keyFn
    ) {
        if (values == null
                || values.isEmpty()) {

            return;
        }

        for (T value : values) {
            if (value == null) {
                continue;
            }

            String key =
                    safe(
                            keyFn.apply(value)
                    );

            if (!target.containsKey(key)) {
                target.put(
                        key,
                        value
                );
            }
        }
    }

    /**
     * Object 형태의 attr 값을 observation key용 문자열로 변환한다.
     */
    private static String safeValue(
            Object value
    ) {
        return value == null
                ? ""
                : String.valueOf(value);
    }

    /**
     * null 문자열을 빈 문자열로 변환한다.
     */
    private static String safe(
            String value
    ) {
        return value == null
                ? ""
                : value;
    }
}