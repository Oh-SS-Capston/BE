package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.service.support.policy.ConfidenceAssessment;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationPolicyInput;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.ResolutionAssessment;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DI_PROVIDER와 CONFIG_WIRING Observation만으로
 * DECLARES_BEAN / CONFIGURES_BEAN shadow Relation 후보를 생성한다.
 *
 * <p>Extraction BeanObservationResolver 및
 * ConfigurationObservationResolver는 직접 호출하지 않는다.</p>
 */
public final class BeanConfigurationShadowCandidateGenerator {

    private static final String DI_PROVIDER =
            "di_provider";

    private static final String CONFIG_WIRING =
            "config_wiring";

    private BeanConfigurationShadowCandidateGenerator() {
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

        List<ObservationPromotionShadowCandidate> candidates =
                new ArrayList<>();

        List<String> warnings =
                new ArrayList<>();

        List<NormalizedObservationFact> observations =
                facts.observations() == null
                        ? List.of()
                        : facts.observations();

        int eligibleObservationCount = 0;

        for (int index = 0;
             index < observations.size();
             index++) {

            NormalizedObservationFact observation =
                    observations.get(index);

            String kind =
                    normalizeCode(
                            observation == null
                                    ? null
                                    : observation.kind()
                    );

            if (DI_PROVIDER.equals(kind)) {
                eligibleObservationCount++;

                generateBeanCandidates(
                        index,
                        observation,
                        mapper,
                        resolutionPolicy,
                        confidencePolicy,
                        candidates,
                        warnings
                );
                continue;
            }

            if (CONFIG_WIRING.equals(kind)) {
                eligibleObservationCount++;

                generateConfigurationCandidates(
                        index,
                        observation,
                        mapper,
                        resolutionPolicy,
                        confidencePolicy,
                        candidates,
                        warnings
                );
            }
        }

        return new ObservationPromotionCandidateGenerationResult(
                eligibleObservationCount,
                candidates,
                warnings
        );
    }

    private static void generateBeanCandidates(
            int observationIndex,
            NormalizedObservationFact provider,
            ObjectMapper mapper,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates,
            List<String> warnings
    ) {
        String siteSymbol =
                trimToNull(provider.siteSymbol());

        if (siteSymbol == null) {
            warnings.add(
                    "DI_PROVIDER observation has no siteSymbol "
                            + "and was skipped: index="
                            + observationIndex
            );
            return;
        }

        Map<String, Object> sourceAttrs =
                attrs(provider, mapper);

        String providedType =
                resolveProvidedType(
                        provider.targetTypeRef(),
                        sourceAttrs.get("provided_type")
                );

        List<String> declaredBeanNames =
                normalizeNames(
                        stringList(
                                sourceAttrs.get("bean_names")
                        )
                );

        NameResolution names =
                declaredBeanNames.isEmpty()
                        ? inferBeanNames(
                                siteSymbol,
                                providedType
                        )
                        : new NameResolution(
                                declaredBeanNames,
                                true,
                                null
                        );

        if (names.beanNames().isEmpty()) {
            warnings.add(
                    "DI_PROVIDER observation could not determine "
                            + "a Bean name: "
                            + siteSymbol
            );
            return;
        }

        for (String beanName : names.beanNames()) {
            candidates.add(
                    buildBeanCandidate(
                            observationIndex,
                            provider,
                            sourceAttrs,
                            beanName,
                            providedType,
                            names.declared(),
                            names.partialReason(),
                            resolutionPolicy,
                            confidencePolicy
                    )
            );
        }
    }

    private static ObservationPromotionShadowCandidate
    buildBeanCandidate(
            int observationIndex,
            NormalizedObservationFact provider,
            Map<String, Object> sourceAttrs,
            String beanName,
            String providedType,
            boolean declaredName,
            String reasonOverride,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy
    ) {
        List<String> evidenceIds =
                sanitizeEvidenceIds(
                        provider.evidenceIds()
                );

        FactOriginKind origin =
                origin(provider.origin());

        RelationPolicyInput input =
                RelationPolicyInput.builder()
                        .origin(origin)
                        .derivation(DerivationKind.DERIVED)
                        .targetReferenceKnown(true)
                        .targetReferenceAuthoritative(true)
                        .inferred(!declaredName)
                        .candidateCount(1)
                        .evidencePresent(
                                !evidenceIds.isEmpty()
                        )
                        .sourceConfidenceHint(
                                confidenceHint(provider)
                        )
                        .build();

        PolicyResult policy =
                assess(
                        input,
                        resolutionPolicy,
                        confidencePolicy,
                        reasonOverride
                );

        Map<String, Object> relationAttrs =
                new LinkedHashMap<>(sourceAttrs);

        relationAttrs.put("bean_name", beanName);
        relationAttrs.put(
                "bean_reference",
                "bean:" + beanName
        );
        relationAttrs.put(
                "name_resolution",
                declaredName
                        ? "declared"
                        : "inferred"
        );
        relationAttrs.put(
                "semantic_kind",
                "bean_declaration"
        );
        relationAttrs.put(
                "resolver",
                "BeanObservationResolver"
        );

        if (providedType != null) {
            relationAttrs.put(
                    "provided_type",
                    providedType
            );
        }

        putPolicyAttrs(
                relationAttrs,
                policy
        );

        NormalizedRelationFact relation =
                relation(
                        "declares_bean",
                        provider.siteSymbol(),
                        "bean:" + beanName,
                        origin,
                        policy,
                        relationAttrs,
                        evidenceIds
                );

        return new ObservationPromotionShadowCandidate(
                observationIndex,
                DI_PROVIDER,
                relation
        );
    }

    private static void generateConfigurationCandidates(
            int observationIndex,
            NormalizedObservationFact observation,
            ObjectMapper mapper,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates,
            List<String> warnings
    ) {
        String siteSymbol =
                trimToNull(observation.siteSymbol());

        if (siteSymbol == null) {
            warnings.add(
                    "CONFIG_WIRING observation has no siteSymbol "
                            + "and was skipped: index="
                            + observationIndex
            );
            return;
        }

        Map<String, Object> sourceAttrs =
                attrs(observation, mapper);

        List<String> importedTypes =
                normalizeTypeNames(
                        stringList(
                                sourceAttrs.get(
                                        "imported_types"
                                )
                        )
                );

        List<String> scanPackages =
                normalizePackages(
                        stringList(
                                sourceAttrs.get(
                                        "component_scan_packages"
                                )
                        )
                );

        for (String importedType : importedTypes) {
            candidates.add(
                    buildConfigurationCandidate(
                            observationIndex,
                            observation,
                            sourceAttrs,
                            "type:" + importedType,
                            "import_type",
                            importedType,
                            resolutionPolicy,
                            confidencePolicy
                    )
            );
        }

        for (String scanPackage : scanPackages) {
            candidates.add(
                    buildConfigurationCandidate(
                            observationIndex,
                            observation,
                            sourceAttrs,
                            "package:" + scanPackage,
                            "component_scan_package",
                            scanPackage,
                            resolutionPolicy,
                            confidencePolicy
                    )
            );
        }
    }

    private static ObservationPromotionShadowCandidate
    buildConfigurationCandidate(
            int observationIndex,
            NormalizedObservationFact observation,
            Map<String, Object> sourceAttrs,
            String targetReference,
            String wiringKind,
            String wiringTarget,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy
    ) {
        List<String> evidenceIds =
                sanitizeEvidenceIds(
                        observation.evidenceIds()
                );

        FactOriginKind origin =
                origin(observation.origin());

        RelationPolicyInput input =
                RelationPolicyInput.builder()
                        .origin(origin)
                        .derivation(DerivationKind.DERIVED)
                        .targetReferenceKnown(true)
                        .targetReferenceAuthoritative(true)
                        .candidateCount(1)
                        .evidencePresent(
                                !evidenceIds.isEmpty()
                        )
                        .sourceConfidenceHint(
                                confidenceHint(observation)
                        )
                        .build();

        PolicyResult policy =
                assess(
                        input,
                        resolutionPolicy,
                        confidencePolicy,
                        null
                );

        Map<String, Object> relationAttrs =
                new LinkedHashMap<>(sourceAttrs);

        relationAttrs.put(
                "wiring_kind",
                wiringKind
        );
        relationAttrs.put(
                "wiring_target",
                wiringTarget
        );
        relationAttrs.put(
                "semantic_kind",
                "configuration_wiring"
        );
        relationAttrs.put(
                "resolver",
                "ConfigurationObservationResolver"
        );

        putPolicyAttrs(
                relationAttrs,
                policy
        );

        NormalizedRelationFact relation =
                relation(
                        "configures_bean",
                        observation.siteSymbol(),
                        targetReference,
                        origin,
                        policy,
                        relationAttrs,
                        evidenceIds
                );

        return new ObservationPromotionShadowCandidate(
                observationIndex,
                CONFIG_WIRING,
                relation
        );
    }

    private static NormalizedRelationFact relation(
            String kind,
            String srcSymbol,
            String dstRawRef,
            FactOriginKind origin,
            PolicyResult policy,
            Map<String, Object> attrs,
            List<String> evidenceIds
    ) {
        return new NormalizedRelationFact(
                kind,
                srcSymbol,
                null,
                dstRawRef,
                origin.code(),
                "derived",
                policy.status(),
                policy.reason(),
                null,
                policy.confidence(),
                attrs,
                evidenceIds
        );
    }

    private static PolicyResult assess(
            RelationPolicyInput input,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            String reasonOverride
    ) {
        ResolutionAssessment resolution =
                resolutionPolicy.assess(input);

        if (reasonOverride != null) {
            resolution = new ResolutionAssessment(
                    resolution.status(),
                    resolution.basis(),
                    reasonOverride
            );
        }

        ConfidenceAssessment confidence =
                confidencePolicy.assess(
                        input,
                        resolution
                );

        return new PolicyResult(
                resolution.status().code(),
                resolution.basis().code(),
                resolution.reason(),
                BigDecimal.valueOf(
                        confidence.value()
                ),
                confidence.band().code(),
                confidence.defaultVisible()
        );
    }

    private static void putPolicyAttrs(
            Map<String, Object> attrs,
            PolicyResult policy
    ) {
        attrs.put(
                "resolution_basis",
                policy.resolutionBasis()
        );
        attrs.put(
                "confidence_band",
                policy.confidenceBand()
        );
        attrs.put(
                "default_visible",
                policy.defaultVisible()
        );
    }

    private static String resolveProvidedType(
            JsonNode targetTypeRef,
            Object rawProvidedType
    ) {
        String fromAttrs =
                trimToNull(
                        rawProvidedType == null
                                ? null
                                : String.valueOf(
                                        rawProvidedType
                                )
                );

        if (fromAttrs != null) {
            return fromAttrs;
        }

        if (targetTypeRef == null
                || targetTypeRef.isNull()) {
            return null;
        }

        if (targetTypeRef.isTextual()) {
            return trimToNull(
                    targetTypeRef.asText()
            );
        }

        JsonNode raw =
                targetTypeRef.get("raw");

        return raw == null || raw.isNull()
                ? null
                : trimToNull(raw.asText());
    }

    /**
     * Extraction BeanObservationResolver와 동일하게
     * provided type을 provider symbol보다 먼저 사용한다.
     */
    private static NameResolution inferBeanNames(
            String siteSymbol,
            String providedType
    ) {
        String fromType =
                defaultBeanNameFromType(
                        providedType
                );

        if (fromType != null) {
            return new NameResolution(
                    List.of(fromType),
                    false,
                    "Bean name inferred from provided type"
            );
        }

        String fromSymbol =
                providerNameFromSymbol(
                        siteSymbol
                );

        if (fromSymbol != null) {
            return new NameResolution(
                    List.of(fromSymbol),
                    false,
                    "Bean name inferred from provider symbol"
            );
        }

        return new NameResolution(
                List.of(),
                false,
                "Bean name could not be resolved"
        );
    }

    private static String defaultBeanNameFromType(
            String providedType
    ) {
        String normalized =
                trimToNull(providedType);

        if (normalized == null
                || "void".equals(normalized)) {
            return null;
        }

        if (normalized.startsWith("type:")) {
            normalized = trimToNull(
                    normalized.substring(
                            "type:".length()
                    )
            );
        }

        if (normalized == null) {
            return null;
        }

        int separator =
                Math.max(
                        normalized.lastIndexOf('.'),
                        normalized.lastIndexOf('$')
                );

        String simpleName =
                separator >= 0
                        ? normalized.substring(
                                separator + 1
                        )
                        : normalized;

        return trimToNull(
                decapitalize(simpleName)
        );
    }

    private static String providerNameFromSymbol(
            String siteSymbol
    ) {
        String symbol =
                trimToNull(siteSymbol);

        if (symbol == null) {
            return null;
        }

        int hashIndex =
                symbol.lastIndexOf('#');

        if (hashIndex >= 0
                && hashIndex + 1 < symbol.length()) {
            String methodPart =
                    symbol.substring(
                            hashIndex + 1
                    );

            int parameterIndex =
                    methodPart.indexOf('(');

            if (parameterIndex >= 0) {
                methodPart =
                        methodPart.substring(
                                0,
                                parameterIndex
                        );
            }

            return trimToNull(methodPart);
        }

        if (symbol.startsWith("type:")) {
            return defaultBeanNameFromType(
                    symbol.substring(
                            "type:".length()
                    )
            );
        }

        return null;
    }

    private static String decapitalize(
            String value
    ) {
        String normalized =
                trimToNull(value);

        if (normalized == null) {
            return null;
        }

        if (normalized.length() > 1
                && Character.isUpperCase(
                        normalized.charAt(0)
                )
                && Character.isUpperCase(
                        normalized.charAt(1)
                )) {
            return normalized;
        }

        return Character.toLowerCase(
                normalized.charAt(0)
        ) + normalized.substring(1);
    }

    private static List<String> normalizeTypeNames(
            List<String> rawTypes
    ) {
        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();

        for (String rawType : rawTypes) {
            String typeName =
                    trimToNull(rawType);

            if (typeName == null) {
                continue;
            }

            if (typeName.startsWith("type:")) {
                typeName = trimToNull(
                        typeName.substring(
                                "type:".length()
                        )
                );
            }

            if (typeName != null
                    && typeName.endsWith(".class")) {
                typeName = trimToNull(
                        typeName.substring(
                                0,
                                typeName.length()
                                        - ".class".length()
                        )
                );
            }

            if (typeName != null) {
                normalized.add(typeName);
            }
        }

        return List.copyOf(normalized);
    }

    private static List<String> normalizePackages(
            List<String> rawPackages
    ) {
        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();

        for (String rawPackage : rawPackages) {
            String packageName =
                    trimToNull(rawPackage);

            if (packageName == null) {
                continue;
            }

            if (packageName.startsWith("package:")) {
                packageName = trimToNull(
                        packageName.substring(
                                "package:".length()
                        )
                );
            }

            while (packageName != null
                    && packageName.endsWith(".")) {
                packageName = trimToNull(
                        packageName.substring(
                                0,
                                packageName.length() - 1
                        )
                );
            }

            if (packageName != null) {
                normalized.add(packageName);
            }
        }

        return List.copyOf(normalized);
    }

    private static List<String> normalizeNames(
            List<String> rawNames
    ) {
        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();

        for (String rawName : rawNames) {
            String name =
                    trimToNull(rawName);

            if (name != null) {
                normalized.add(name);
            }
        }

        return List.copyOf(normalized);
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

    private static List<String> stringList(
            Object raw
    ) {
        if (raw == null) {
            return List.of();
        }

        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        collectStrings(raw, result);

        return List.copyOf(result);
    }

    private static void collectStrings(
            Object raw,
            Collection<String> destination
    ) {
        if (raw == null) {
            return;
        }

        if (raw instanceof CharSequence sequence) {
            String value =
                    trimToNull(sequence.toString());

            if (value != null) {
                destination.add(value);
            }

            return;
        }

        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectStrings(
                        item,
                        destination
                );
            }

            return;
        }

        if (raw.getClass().isArray()) {
            int length =
                    java.lang.reflect.Array
                            .getLength(raw);

            for (int index = 0;
                 index < length;
                 index++) {
                collectStrings(
                        java.lang.reflect.Array.get(
                                raw,
                                index
                        ),
                        destination
                );
            }
        }
    }

    private static List<String> sanitizeEvidenceIds(
            List<String> evidenceIds
    ) {
        if (evidenceIds == null
                || evidenceIds.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> sanitized =
                new LinkedHashSet<>();

        for (String evidenceId : evidenceIds) {
            String value =
                    trimToNull(evidenceId);

            if (value != null) {
                sanitized.add(value);
            }
        }

        return List.copyOf(sanitized);
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
            case "ast" -> FactOriginKind.AST;
            case "bytecode" ->
                    FactOriginKind.BYTECODE;
            case "ast_and_bytecode", "merged" ->
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

    private record NameResolution(
            List<String> beanNames,
            boolean declared,
            String partialReason
    ) {
    }

    private record PolicyResult(
            String status,
            String resolutionBasis,
            String reason,
            BigDecimal confidence,
            String confidenceBand,
            boolean defaultVisible
    ) {
    }
}
