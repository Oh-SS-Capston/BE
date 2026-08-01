package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.service.support.policy.ConfidenceAssessment;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationPolicyInput;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.ResolutionAssessment;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REFLECTION_SITE Observation과 GraphStore normalized symbol만으로
 * REFLECTS_TYPE / METHOD / FIELD / CONSTRUCTOR shadow 후보를 생성한다.
 *
 * <p>Extraction ReflectionObservationResolver는 직접 호출하지 않는다.</p>
 */
public final class ReflectionShadowCandidateGenerator {

    private static final String REFLECTION_SITE =
            "reflection_site";

    private static final String UNKNOWN_TYPE =
            "<unresolved-reflection-type>";

    private static final String UNKNOWN_MEMBER =
            "<unresolved-reflection-member>";

    private ReflectionShadowCandidateGenerator() {
    }

    public static ObservationPromotionCandidateGenerationResult
    generate(
            NormalizedFactsDocument facts,
            ObjectMapper objectMapper
    ) {
        if (facts == null) {
            return new ObservationPromotionCandidateGenerationResult(
                    0,
                    List.of(),
                    List.of()
            );
        }

        ObjectMapper mapper =
                objectMapper == null
                        ? new ObjectMapper()
                                .findAndRegisterModules()
                        : objectMapper;

        RelationResolutionPolicy resolutionPolicy =
                new RelationResolutionPolicy();

        RelationConfidencePolicy confidencePolicy =
                new RelationConfidencePolicy();

        List<NormalizedSymbolFact> symbols =
                facts.symbols() == null
                        ? List.of()
                        : facts.symbols();

        List<NormalizedObservationFact> observations =
                facts.observations() == null
                        ? List.of()
                        : facts.observations();

        List<ObservationPromotionShadowCandidate> candidates =
                new ArrayList<>();

        List<String> warnings =
                new ArrayList<>();

        int eligibleObservationCount = 0;

        for (int index = 0;
             index < observations.size();
             index++) {

            NormalizedObservationFact observation =
                    observations.get(index);

            if (!REFLECTION_SITE.equals(
                    normalizeCode(
                            observation == null
                                    ? null
                                    : observation.kind()
                    )
            )) {
                continue;
            }

            eligibleObservationCount++;

            ObservationPromotionShadowCandidate candidate =
                    generateCandidate(
                            index,
                            observation,
                            symbols,
                            mapper,
                            resolutionPolicy,
                            confidencePolicy,
                            warnings
                    );

            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        return new ObservationPromotionCandidateGenerationResult(
                eligibleObservationCount,
                candidates,
                warnings
        );
    }

    private static ObservationPromotionShadowCandidate
    generateCandidate(
            int observationIndex,
            NormalizedObservationFact observation,
            List<NormalizedSymbolFact> symbols,
            ObjectMapper mapper,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<String> warnings
    ) {
        if (observation == null) {
            return null;
        }

        String siteSymbol =
                trimToNull(observation.siteSymbol());

        if (siteSymbol == null) {
            warnings.add(
                    "REFLECTION_SITE observation has no siteSymbol "
                            + "and was skipped: index="
                            + observationIndex
            );
            return null;
        }

        Map<String, Object> sourceAttrs =
                attrs(observation, mapper);

        String apiMethod =
                firstString(
                        sourceAttrs,
                        "api_method",
                        "method"
                );

        ReflectionKind reflectionKind =
                reflectionKind(
                        firstString(
                                sourceAttrs,
                                "reflection_kind"
                        ),
                        apiMethod,
                        firstString(
                                sourceAttrs,
                                "owner",
                                "api_owner"
                        )
                );

        String targetType =
                targetType(
                        observation,
                        sourceAttrs
                );

        String memberName =
                firstString(
                        sourceAttrs,
                        "member_name"
                );

        List<String> parameterTypes =
                stringList(
                        sourceAttrs.get(
                                "parameter_types"
                        )
                );

        ResolutionTarget target =
                switch (reflectionKind) {
                    case TYPE ->
                            resolveTypeTarget(
                                    targetType,
                                    symbols
                            );

                    case METHOD ->
                            resolveMethodTarget(
                                    targetType,
                                    memberName,
                                    parameterTypes,
                                    symbols
                            );

                    case FIELD ->
                            resolveFieldTarget(
                                    targetType,
                                    memberName,
                                    symbols
                            );

                    case CONSTRUCTOR ->
                            resolveConstructorTarget(
                                    targetType,
                                    parameterTypes,
                                    symbols
                            );

                    case UNKNOWN ->
                            ResolutionTarget.partial(
                                    "reflects_type",
                                    "reflection:"
                                            + (apiMethod == null
                                            ? "unknown"
                                            : apiMethod),
                                    "Reflection API kind could not be classified",
                                    "unknown_api",
                                    apiMethod != null,
                                    0
                            );
                };

        List<String> evidenceIds =
                sanitizeEvidenceIds(
                        observation.evidenceIds()
                );

        FactOriginKind origin =
                origin(observation.origin());

        RelationPolicyInput policyInput =
                RelationPolicyInput.builder()
                        .origin(origin)
                        .derivation(
                                DerivationKind.DERIVED
                        )
                        .targetSymbolResolved(
                                target.symbolResolved()
                        )
                        .targetReferenceKnown(
                                target.referenceKnown()
                        )
                        .targetReferenceAuthoritative(
                                target.referenceAuthoritative()
                        )
                        .inferred(target.inferred())
                        .candidateCount(
                                target.candidateCount()
                        )
                        .evidencePresent(
                                !evidenceIds.isEmpty()
                        )
                        .sourceConfidenceHint(
                                confidenceHint(observation)
                        )
                        .build();

        ResolutionAssessment policyResolution =
                resolutionPolicy.assess(
                        policyInput
                );

        ResolutionAssessment resolution =
                target.reason() == null
                        || policyResolution.status()
                        == ResolutionStatus.RESOLVED
                        ? policyResolution
                        : new ResolutionAssessment(
                                policyResolution.status(),
                                policyResolution.basis(),
                                target.reason()
                        );

        ConfidenceAssessment confidence =
                confidencePolicy.assess(
                        policyInput,
                        resolution
                );

        Map<String, Object> relationAttrs =
                new LinkedHashMap<>(sourceAttrs);

        relationAttrs.put(
                "resolver",
                "ReflectionObservationResolver"
        );
        relationAttrs.put(
                "semantic_kind",
                "reflection_reference"
        );
        relationAttrs.put(
                "reflection_kind",
                reflectionKind.code()
        );
        relationAttrs.put(
                "match_strategy",
                target.matchStrategy()
        );
        relationAttrs.put(
                "target_resolution",
                resolution.status()
                        .name()
                        .toLowerCase(Locale.ROOT)
        );
        relationAttrs.put(
                "resolution_basis",
                resolution.basis().code()
        );
        relationAttrs.put(
                "confidence_band",
                confidence.band().code()
        );
        relationAttrs.put(
                "default_visible",
                confidence.defaultVisible()
        );

        if (target.candidateCount() > 1) {
            relationAttrs.put(
                    "candidate_count",
                    target.candidateCount()
            );
        }

        if (target.inferred()) {
            relationAttrs.put(
                    "inferred_match",
                    true
            );
        }

        if (apiMethod != null) {
            relationAttrs.put(
                    "api_method",
                    apiMethod
            );
        }

        if (targetType != null) {
            relationAttrs.put(
                    "target_type",
                    targetType
            );
        }

        if (memberName != null) {
            relationAttrs.put(
                    "member_name",
                    memberName
            );
        }

        NormalizedRelationFact relation =
                new NormalizedRelationFact(
                        target.relationKind(),
                        siteSymbol,
                        target.dstSymbol(),
                        target.dstRawRef(),
                        origin.code(),
                        "derived",
                        resolution.status().code(),
                        resolution.reason(),
                        null,
                        BigDecimal.valueOf(
                                confidence.value()
                        ),
                        Collections.unmodifiableMap(
                                new LinkedHashMap<>(
                                        relationAttrs
                                )
                        ),
                        evidenceIds
                );

        return new ObservationPromotionShadowCandidate(
                observationIndex,
                REFLECTION_SITE,
                relation
        );
    }

    private static ResolutionTarget resolveTypeTarget(
            String rawTargetType,
            List<NormalizedSymbolFact> symbols
    ) {
        String normalizedType =
                normalizeRawType(rawTargetType);

        if (normalizedType == null) {
            return ResolutionTarget.partial(
                    "reflects_type",
                    "type:" + UNKNOWN_TYPE,
                    "Reflection target type could not be statically determined",
                    "unresolved_type",
                    false,
                    0
            );
        }

        List<NormalizedSymbolFact> matches =
                matchingTypes(
                        symbols,
                        normalizedType
                );

        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    "reflects_type",
                    matches.get(0).symbol(),
                    "exact_type_symbol"
            );
        }

        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    "reflects_type",
                    "type:" + normalizedType,
                    "Multiple extracted types matched the reflection target",
                    "ambiguous_type",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                "reflects_type",
                "type:" + normalizedType,
                "Reflection target type is statically known but not present in extracted symbols",
                "static_type_raw_ref",
                true,
                0
        );
    }

    private static ResolutionTarget resolveMethodTarget(
            String rawTargetType,
            String memberName,
            List<String> parameterTypes,
            List<NormalizedSymbolFact> symbols
    ) {
        String normalizedType =
                normalizeRawType(rawTargetType);

        String normalizedMember =
                trimToNull(memberName);

        if (normalizedType == null
                || normalizedMember == null) {
            return ResolutionTarget.partial(
                    "reflects_method",
                    methodRawRef(
                            normalizedType,
                            normalizedMember,
                            parameterTypes
                    ),
                    "Reflection method owner or member name could not be statically determined",
                    "unresolved_method",
                    normalizedType != null
                            || normalizedMember != null
                            || (parameterTypes != null
                            && !parameterTypes.isEmpty()),
                    0
            );
        }

        List<NormalizedSymbolFact> matches =
                matchingMembers(
                        symbolsOfKind(
                                symbols,
                                "method"
                        ),
                        normalizedType,
                        normalizedMember,
                        parameterTypes
                );

        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    "reflects_method",
                    matches.get(0).symbol(),
                    parameterTypes.isEmpty()
                            ? "exact_method_name"
                            : "exact_method_signature"
            );
        }

        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    "reflects_method",
                    methodRawRef(
                            normalizedType,
                            normalizedMember,
                            parameterTypes
                    ),
                    "Multiple reflected method candidates matched the extracted symbols",
                    "ambiguous_method_overload",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                "reflects_method",
                methodRawRef(
                        normalizedType,
                        normalizedMember,
                        parameterTypes
                ),
                "Reflected method was statically described but no extracted symbol matched",
                "static_method_raw_ref",
                true,
                0
        );
    }

    private static ResolutionTarget resolveFieldTarget(
            String rawTargetType,
            String memberName,
            List<NormalizedSymbolFact> symbols
    ) {
        String normalizedType =
                normalizeRawType(rawTargetType);

        String normalizedMember =
                trimToNull(memberName);

        if (normalizedType == null
                || normalizedMember == null) {
            return ResolutionTarget.partial(
                    "reflects_field",
                    fieldRawRef(
                            normalizedType,
                            normalizedMember
                    ),
                    "Reflection field owner or member name could not be statically determined",
                    "unresolved_field",
                    normalizedType != null
                            || normalizedMember != null,
                    0
            );
        }

        List<NormalizedSymbolFact> matches =
                matchingMembers(
                        symbolsOfKind(
                                symbols,
                                "field"
                        ),
                        normalizedType,
                        normalizedMember,
                        List.of()
                );

        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    "reflects_field",
                    matches.get(0).symbol(),
                    "exact_field_symbol"
            );
        }

        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    "reflects_field",
                    fieldRawRef(
                            normalizedType,
                            normalizedMember
                    ),
                    "Multiple reflected field candidates matched the extracted symbols",
                    "ambiguous_field",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                "reflects_field",
                fieldRawRef(
                        normalizedType,
                        normalizedMember
                ),
                "Reflected field was statically described but no extracted symbol matched",
                "static_field_raw_ref",
                true,
                0
        );
    }

    private static ResolutionTarget resolveConstructorTarget(
            String rawTargetType,
            List<String> parameterTypes,
            List<NormalizedSymbolFact> symbols
    ) {
        String normalizedType =
                normalizeRawType(rawTargetType);

        if (normalizedType == null) {
            return ResolutionTarget.partial(
                    "reflects_constructor",
                    constructorRawRef(
                            null,
                            parameterTypes
                    ),
                    "Reflection constructor target type could not be statically determined",
                    "unresolved_constructor",
                    parameterTypes != null
                            && !parameterTypes.isEmpty(),
                    0
            );
        }

        List<NormalizedSymbolFact> matches =
                matchingMembers(
                        symbolsOfKind(
                                symbols,
                                "constructor"
                        ),
                        normalizedType,
                        null,
                        parameterTypes
                );

        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    "reflects_constructor",
                    matches.get(0).symbol(),
                    parameterTypes.isEmpty()
                            ? "exact_constructor_owner"
                            : "exact_constructor_signature"
            );
        }

        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    "reflects_constructor",
                    constructorRawRef(
                            normalizedType,
                            parameterTypes
                    ),
                    "Multiple reflected constructor candidates matched the extracted symbols",
                    "ambiguous_constructor",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                "reflects_constructor",
                constructorRawRef(
                        normalizedType,
                        parameterTypes
                ),
                "Reflected constructor was statically described but no extracted symbol matched",
                "static_constructor_raw_ref",
                true,
                0
        );
    }

    private static List<NormalizedSymbolFact> matchingTypes(
            List<NormalizedSymbolFact> symbols,
            String rawType
    ) {
        return symbolsOfKind(
                symbols,
                "type"
        ).stream()
                .filter(symbol ->
                        typeMatches(
                                symbol,
                                rawType
                        )
                )
                .toList();
    }

    private static List<NormalizedSymbolFact> matchingMembers(
            List<NormalizedSymbolFact> candidates,
            String rawOwnerType,
            String memberName,
            List<String> parameterTypes
    ) {
        if (candidates == null
                || candidates.isEmpty()) {
            return List.of();
        }

        List<NormalizedSymbolFact> ownerAndNameMatches =
                candidates.stream()
                        .filter(symbol ->
                                symbol != null
                        )
                        .filter(symbol ->
                                ownerMatches(
                                        symbol.ownerTypeSymbol(),
                                        rawOwnerType
                                )
                        )
                        .filter(symbol ->
                                memberName == null
                                        || memberName.equals(
                                        symbol.name()
                                )
                        )
                        .toList();

        if (parameterTypes == null
                || parameterTypes.isEmpty()) {
            return ownerAndNameMatches;
        }

        List<NormalizedSymbolFact> signatureMatches =
                ownerAndNameMatches.stream()
                        .filter(symbol ->
                                symbolContainsParameterTypes(
                                        symbol.symbol(),
                                        parameterTypes
                                )
                        )
                        .toList();

        return signatureMatches.isEmpty()
                ? ownerAndNameMatches
                : signatureMatches;
    }

    private static List<NormalizedSymbolFact> symbolsOfKind(
            List<NormalizedSymbolFact> symbols,
            String kind
    ) {
        if (symbols == null
                || symbols.isEmpty()) {
            return List.of();
        }

        return symbols.stream()
                .filter(symbol ->
                        symbol != null
                )
                .filter(symbol ->
                        kind.equals(
                                normalizeCode(
                                        symbol.kind()
                                )
                        )
                )
                .toList();
    }

    private static boolean typeMatches(
            NormalizedSymbolFact symbol,
            String rawType
    ) {
        String typeSymbol =
                normalizeTypeSymbol(rawType);

        if (typeSymbol.equals(
                symbol.symbol()
        )) {
            return true;
        }

        String qualifiedName =
                trimToNull(
                        symbol.qualifiedName()
                );

        if (rawType.equals(
                qualifiedName
        )) {
            return true;
        }

        String simple =
                simpleName(rawType);

        return simple.equals(
                symbol.name()
        )
                || (symbol.symbol() != null
                && symbol.symbol().endsWith(
                "." + simple
        ));
    }

    private static boolean ownerMatches(
            String ownerSymbol,
            String rawOwnerType
    ) {
        if (ownerSymbol == null
                || rawOwnerType == null) {
            return false;
        }

        if (normalizeTypeSymbol(
                rawOwnerType
        ).equals(ownerSymbol)) {
            return true;
        }

        return ownerSymbol.endsWith(
                "." + simpleName(rawOwnerType)
        );
    }

    private static boolean symbolContainsParameterTypes(
            String symbol,
            List<String> parameterTypes
    ) {
        if (symbol == null) {
            return false;
        }

        for (String parameterType : parameterTypes) {
            String normalized =
                    normalizeRawType(
                            parameterType
                    );

            if (normalized == null) {
                continue;
            }

            if (!symbol.contains(normalized)
                    && !symbol.contains(
                    simpleName(normalized)
            )) {
                return false;
            }
        }

        return true;
    }

    private static String targetType(
            NormalizedObservationFact observation,
            Map<String, Object> attrs
    ) {
        String targetSymbol =
                trimToNull(
                        observation.targetSymbol()
                );

        if (targetSymbol != null) {
            return normalizeRawType(
                    targetSymbol
            );
        }

        JsonNode typeRef =
                observation.targetTypeRef();

        if (typeRef != null
                && !typeRef.isNull()) {
            String raw =
                    firstNonBlank(
                            text(
                                    typeRef,
                                    "raw"
                            ),
                            firstNonBlank(
                                    text(
                                            typeRef,
                                            "source_text"
                                    ),
                                    text(
                                            typeRef,
                                            "sourceText"
                                    )
                            )
                    );

            if (raw != null) {
                return normalizeRawType(raw);
            }
        }

        return normalizeRawType(
                firstString(
                        attrs,
                        "target_type",
                        "class_name",
                        "owner_type"
                )
        );
    }

    private static ReflectionKind reflectionKind(
            String explicitKind,
            String apiMethod,
            String apiOwner
    ) {
        String normalized =
                trimToNull(explicitKind);

        if (normalized != null) {
            return switch (
                    normalized.toLowerCase(
                            Locale.ROOT
                    )
            ) {
                case "type" ->
                        ReflectionKind.TYPE;

                case "method" ->
                        ReflectionKind.METHOD;

                case "field" ->
                        ReflectionKind.FIELD;

                case "constructor" ->
                        ReflectionKind.CONSTRUCTOR;

                default ->
                        ReflectionKind.UNKNOWN;
            };
        }

        if ("forName".equals(apiMethod)) {
            return ReflectionKind.TYPE;
        }

        if ("getMethod".equals(apiMethod)
                || "getDeclaredMethod".equals(
                apiMethod
        )
                || "invoke".equals(apiMethod)) {
            return ReflectionKind.METHOD;
        }

        if ("getField".equals(apiMethod)
                || "getDeclaredField".equals(
                apiMethod
        )
                || "java.lang.reflect.Field".equals(
                apiOwner
        )) {
            return ReflectionKind.FIELD;
        }

        if ("getConstructor".equals(apiMethod)
                || "getDeclaredConstructor".equals(
                apiMethod
        )
                || "newInstance".equals(apiMethod)) {
            return ReflectionKind.CONSTRUCTOR;
        }

        return ReflectionKind.UNKNOWN;
    }

    private static String methodRawRef(
            String owner,
            String member,
            List<String> parameterTypes
    ) {
        return "method:"
                + (owner == null
                ? UNKNOWN_TYPE
                : owner)
                + "#"
                + (member == null
                ? UNKNOWN_MEMBER
                : member)
                + "("
                + String.join(
                        ",",
                        parameterTypes == null
                                ? List.of()
                                : parameterTypes
                )
                + ")";
    }

    private static String fieldRawRef(
            String owner,
            String member
    ) {
        return "field:"
                + (owner == null
                ? UNKNOWN_TYPE
                : owner)
                + "#"
                + (member == null
                ? UNKNOWN_MEMBER
                : member);
    }

    private static String constructorRawRef(
            String owner,
            List<String> parameterTypes
    ) {
        return "ctor:"
                + (owner == null
                ? UNKNOWN_TYPE
                : owner)
                + "("
                + String.join(
                        ",",
                        parameterTypes == null
                                ? List.of()
                                : parameterTypes
                )
                + ")";
    }

    private static Map<String, Object> attrs(
            NormalizedObservationFact observation,
            ObjectMapper mapper
    ) {
        if (observation == null
                || observation.attrs() == null
                || observation.attrs().isNull()) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> converted =
                mapper.convertValue(
                        observation.attrs(),
                        new TypeReference<
                                Map<String, Object>
                                >() {
                        }
                );

        Map<String, Object> result =
                new LinkedHashMap<>();

        if (converted != null) {
            for (Map.Entry<String, Object> entry
                    : converted.entrySet()) {
                if (entry.getKey() != null
                        && entry.getValue() != null) {
                    result.put(
                            entry.getKey(),
                            entry.getValue()
                    );
                }
            }
        }

        return result;
    }

    private static String firstString(
            Map<String, Object> attrs,
            String... keys
    ) {
        if (attrs == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            Object value =
                    attrs.get(key);

            if (value == null) {
                continue;
            }

            String normalized =
                    trimToNull(
                            String.valueOf(value)
                    );

            if (normalized != null) {
                return normalized;
            }
        }

        return null;
    }

    private static List<String> stringList(
            Object value
    ) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }

        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        for (Object item : iterable) {
            String normalized =
                    item == null
                            ? null
                            : trimToNull(
                            String.valueOf(item)
                    );

            if (normalized != null) {
                result.add(normalized);
            }
        }

        return List.copyOf(result);
    }

    private static List<String> sanitizeEvidenceIds(
            List<String> source
    ) {
        if (source == null
                || source.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        for (String value : source) {
            String normalized =
                    trimToNull(value);

            if (normalized != null) {
                result.add(normalized);
            }
        }

        return List.copyOf(result);
    }

    private static String normalizeTypeSymbol(
            String value
    ) {
        String raw =
                normalizeRawType(value);

        return "type:"
                + (raw == null
                ? UNKNOWN_TYPE
                : raw);
    }

    private static String normalizeRawType(
            String value
    ) {
        String normalized =
                trimToNull(value);

        if (normalized == null) {
            return null;
        }

        if (normalized.startsWith(
                "type:"
        )) {
            return normalized.substring(
                    "type:".length()
            );
        }

        if (normalized.endsWith(
                ".class"
        )) {
            return normalized.substring(
                    0,
                    normalized.length()
                            - ".class".length()
            );
        }

        return normalized;
    }

    private static String simpleName(
            String value
    ) {
        String normalized =
                normalizeRawType(value);

        if (normalized == null) {
            return "";
        }

        int index =
                Math.max(
                        normalized.lastIndexOf('.'),
                        normalized.lastIndexOf('$')
                );

        return index < 0
                ? normalized
                : normalized.substring(
                        index + 1
                );
    }

    private static String text(
            JsonNode node,
            String field
    ) {
        if (node == null || field == null) {
            return null;
        }

        JsonNode value =
                node.get(field);

        return value == null
                || value.isNull()
                ? null
                : trimToNull(
                value.asText()
        );
    }

    private static String firstNonBlank(
            String first,
            String second
    ) {
        String normalized =
                trimToNull(first);

        return normalized != null
                ? normalized
                : trimToNull(second);
    }

    private static FactOriginKind origin(
            String rawOrigin
    ) {
        String normalized =
                normalizeCode(rawOrigin);

        if (normalized == null) {
            return FactOriginKind.OBSERVED;
        }

        return switch (normalized) {
            case "ast" ->
                    FactOriginKind.AST;

            case "bytecode" ->
                    FactOriginKind.BYTECODE;

            case "ast_and_bytecode",
                 "merged" ->
                    FactOriginKind.AST_AND_BYTECODE;

            case "resource" ->
                    FactOriginKind.RESOURCE;

            case "observed" ->
                    FactOriginKind.OBSERVED;

            default ->
                    FactOriginKind.OBSERVED;
        };
    }

    private static Double confidenceHint(
            NormalizedObservationFact observation
    ) {
        return observation == null
                || observation.confidenceHint() == null
                ? null
                : observation.confidenceHint()
                        .doubleValue();
    }

    private static String normalizeCode(
            String value
    ) {
        String normalized =
                trimToNull(value);

        return normalized == null
                ? null
                : normalized.toLowerCase(
                        Locale.ROOT
                );
    }

    private static String trimToNull(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private enum ReflectionKind {
        TYPE("type"),
        METHOD("method"),
        FIELD("field"),
        CONSTRUCTOR("constructor"),
        UNKNOWN("unknown");

        private final String code;

        ReflectionKind(String code) {
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    private record ResolutionTarget(
            String relationKind,
            String dstSymbol,
            String dstRawRef,
            boolean symbolResolved,
            boolean referenceKnown,
            boolean referenceAuthoritative,
            boolean inferred,
            int candidateCount,
            String reason,
            String matchStrategy
    ) {

        private ResolutionTarget {
            candidateCount =
                    Math.max(
                            0,
                            candidateCount
                    );
        }

        private static ResolutionTarget resolved(
                String relationKind,
                String dstSymbol,
                String matchStrategy
        ) {
            return new ResolutionTarget(
                    relationKind,
                    dstSymbol,
                    null,
                    true,
                    true,
                    true,
                    false,
                    1,
                    null,
                    matchStrategy
            );
        }

        private static ResolutionTarget partial(
                String relationKind,
                String dstRawRef,
                String reason,
                String matchStrategy,
                boolean referenceKnown,
                int candidateCount
        ) {
            return new ResolutionTarget(
                    relationKind,
                    null,
                    dstRawRef,
                    false,
                    referenceKnown,
                    false,
                    false,
                    candidateCount,
                    reason,
                    matchStrategy
            );
        }
    }
}
