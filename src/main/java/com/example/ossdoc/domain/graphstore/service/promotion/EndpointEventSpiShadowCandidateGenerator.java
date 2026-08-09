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
import java.util.Objects;
import java.util.Set;

/**
 * Endpoint·Event·SPI Observation만으로 GraphStore shadow Relation 후보를 생성한다.
 *
 * <p>Extraction resolver 자체는 호출하지 않는다. 다만 resolution/confidence의
 * 수치 계약이 불필요하게 분기되지 않도록 현재 공통 policy 객체를 재사용한다.
 * 해당 policy의 중립 패키지 이동은 책임 전환 마지막 단계에서 수행한다.</p>
 */
public final class EndpointEventSpiShadowCandidateGenerator {

    private static final String UNRESOLVED_PATH =
            "<unresolved-path>";
    private static final String CONFLICTING_METHOD =
            "<conflicting-method>";
    private static final String UNRESOLVED_EVENT =
            "<unresolved-event>";
    private static final String UNRESOLVED_SERVICE =
            "<unresolved-service>";

    private static final Set<String> SUPPORTED_KINDS =
            Set.of(
                    "http_endpoint",
                    "event_publication",
                    "event_subscription",
                    "spi_provider",
                    "module_uses",
                    "module_provides"
            );

    private EndpointEventSpiShadowCandidateGenerator() {
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

        ObjectMapper mapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
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

            String kind = normalizeCode(
                    observation == null
                            ? null
                            : observation.kind()
            );

            if (!SUPPORTED_KINDS.contains(kind)) {
                continue;
            }

            eligibleObservationCount++;

            switch (kind) {
                case "http_endpoint" ->
                        generateEndpoint(
                                index,
                                observation,
                                mapper,
                                resolutionPolicy,
                                confidencePolicy,
                                candidates,
                                warnings
                        );

                case "event_publication" ->
                        generateEvent(
                                index,
                                observation,
                                "publishes_event",
                                "event_publication",
                                "EventObservationResolver",
                                mapper,
                                resolutionPolicy,
                                confidencePolicy,
                                candidates,
                                warnings
                        );

                case "event_subscription" ->
                        generateEvent(
                                index,
                                observation,
                                "listens_event",
                                "event_subscription",
                                "EventObservationResolver",
                                mapper,
                                resolutionPolicy,
                                confidencePolicy,
                                candidates,
                                warnings
                        );

                case "spi_provider",
                     "module_uses",
                     "module_provides" ->
                        generateSpi(
                                index,
                                observation,
                                kind,
                                mapper,
                                resolutionPolicy,
                                confidencePolicy,
                                candidates,
                                warnings
                        );

                default -> {
                    // SUPPORTED_KINDS와 switch가 어긋나면 즉시 드러나도록 유지.
                    throw new IllegalStateException(
                            "Unhandled shadow candidate kind: "
                                    + kind
                    );
                }
            }
        }

        return new ObservationPromotionCandidateGenerationResult(
                eligibleObservationCount,
                candidates,
                warnings
        );
    }

    private static void generateEndpoint(
            int observationIndex,
            NormalizedObservationFact observation,
            ObjectMapper mapper,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates,
            List<String> warnings
    ) {
        String siteSymbol = trimToNull(
                observation.siteSymbol()
        );

        if (siteSymbol == null) {
            warnings.add(
                    "HTTP_ENDPOINT observation has no siteSymbol "
                            + "and was skipped: index="
                            + observationIndex
            );
            return;
        }

        Map<String, Object> sourceAttrs =
                attrs(observation, mapper);

        List<String> methods = normalizeHttpMethods(
                stringList(sourceAttrs.get("http_methods"))
        );

        List<String> paths = normalizeHttpPaths(
                stringList(sourceAttrs.get("paths"))
        );

        boolean mappingConflict =
                booleanValue(
                        sourceAttrs.get("mapping_conflict")
                );

        boolean unresolvedPath =
                "unresolved".equalsIgnoreCase(
                        Objects.toString(
                                sourceAttrs.get("path_resolution"),
                                ""
                        )
                )
                        || paths.isEmpty();

        if (mappingConflict) {
            List<String> conflictPaths =
                    paths.isEmpty()
                            ? List.of(UNRESOLVED_PATH)
                            : paths;

            for (String path : conflictPaths) {
                addEndpointCandidate(
                        observationIndex,
                        observation,
                        CONFLICTING_METHOD,
                        path,
                        false,
                        true,
                        2,
                        "HTTP endpoint method mapping conflict",
                        sourceAttrs,
                        resolutionPolicy,
                        confidencePolicy,
                        candidates
                );
            }

            return;
        }

        if (methods.isEmpty()) {
            methods = List.of("ANY");
        }

        if (unresolvedPath) {
            for (String method : methods) {
                addEndpointCandidate(
                        observationIndex,
                        observation,
                        method,
                        UNRESOLVED_PATH,
                        false,
                        true,
                        1,
                        "HTTP endpoint path could not be resolved",
                        sourceAttrs,
                        resolutionPolicy,
                        confidencePolicy,
                        candidates
                );
            }

            return;
        }

        for (String method : methods) {
            for (String path : paths) {
                addEndpointCandidate(
                        observationIndex,
                        observation,
                        method,
                        path,
                        true,
                        true,
                        1,
                        null,
                        sourceAttrs,
                        resolutionPolicy,
                        confidencePolicy,
                        candidates
                );
            }
        }
    }

    private static void addEndpointCandidate(
            int observationIndex,
            NormalizedObservationFact observation,
            String httpMethod,
            String path,
            boolean authoritativeReference,
            boolean referenceKnown,
            int candidateCount,
            String reasonOverride,
            Map<String, Object> sourceAttrs,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates
    ) {
        String normalizedMethod =
                trimToNull(httpMethod) == null
                        ? "ANY"
                        : httpMethod.trim()
                                .toUpperCase(Locale.ROOT);

        String normalizedPath =
                UNRESOLVED_PATH.equals(path)
                        ? UNRESOLVED_PATH
                        : normalizeHttpPath(path);

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
                        .targetReferenceKnown(referenceKnown)
                        .targetReferenceAuthoritative(
                                authoritativeReference
                        )
                        .candidateCount(candidateCount)
                        .evidencePresent(!evidenceIds.isEmpty())
                        .sourceConfidenceHint(
                                confidenceHint(observation)
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

        relationAttrs.put(
                "http_method",
                normalizedMethod
        );
        relationAttrs.put("path", normalizedPath);
        relationAttrs.put(
                "semantic_kind",
                "http_endpoint"
        );
        relationAttrs.put(
                "resolver",
                "EndpointObservationResolver"
        );
        putPolicyAttrs(relationAttrs, policy);

        NormalizedRelationFact relation =
                relation(
                        "handles_endpoint",
                        observation.siteSymbol(),
                        null,
                        normalizedMethod
                                + " "
                                + normalizedPath,
                        origin,
                        policy,
                        relationAttrs,
                        evidenceIds
                );

        candidates.add(
                new ObservationPromotionShadowCandidate(
                        observationIndex,
                        "http_endpoint",
                        relation
                )
        );
    }

    private static void generateEvent(
            int observationIndex,
            NormalizedObservationFact observation,
            String relationKind,
            String semanticKind,
            String resolver,
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
                    observation.kind()
                            + " observation has no siteSymbol "
                            + "and was skipped: index="
                            + observationIndex
            );
            return;
        }

        PromotionTarget target =
                target(
                        observation,
                        UNRESOLVED_EVENT,
                        "event:"
                );

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
                        .targetSymbolResolved(
                                target.symbolResolved()
                        )
                        .targetReferenceKnown(
                                target.referenceKnown()
                        )
                        .targetReferenceAuthoritative(
                                target.referenceAuthoritative()
                        )
                        .inferred(false)
                        .candidateCount(
                                target.referenceKnown() ? 1 : 0
                        )
                        .evidencePresent(!evidenceIds.isEmpty())
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
                attrs(observation, mapper);

        relationAttrs.put(
                "semantic_kind",
                semanticKind
        );
        relationAttrs.put("resolver", resolver);
        relationAttrs.put(
                "event_type",
                target.rawType()
        );
        relationAttrs.put(
                "target_resolution",
                policy.status()
        );
        putPolicyAttrs(relationAttrs, policy);

        String dstSymbol = target.typeSymbol();
        String dstRawRef =
                dstSymbol == null
                        ? "event:" + target.rawType()
                        : null;

        NormalizedRelationFact relation =
                relation(
                        relationKind,
                        siteSymbol,
                        dstSymbol,
                        dstRawRef,
                        origin,
                        policy,
                        relationAttrs,
                        evidenceIds
                );

        candidates.add(
                new ObservationPromotionShadowCandidate(
                        observationIndex,
                        semanticKind,
                        relation
                )
        );
    }

    private static void generateSpi(
            int observationIndex,
            NormalizedObservationFact observation,
            String observationKind,
            ObjectMapper mapper,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates,
            List<String> warnings
    ) {
        Map<String, Object> sourceAttrs =
                attrs(observation, mapper);

        String implementation =
                firstString(
                        sourceAttrs,
                        "implementation",
                        "implementation_type",
                        "provider_type"
                );

        if ("spi_provider".equals(observationKind)
                && implementation == null) {
            addSpiLoadCandidate(
                    observationIndex,
                    observation,
                    "service_loader",
                    sourceAttrs,
                    resolutionPolicy,
                    confidencePolicy,
                    candidates,
                    warnings
            );
            return;
        }

        if ("module_uses".equals(observationKind)) {
            addSpiLoadCandidate(
                    observationIndex,
                    observation,
                    "module_uses",
                    sourceAttrs,
                    resolutionPolicy,
                    confidencePolicy,
                    candidates,
                    warnings
            );
            return;
        }

        String mechanism =
                "module_provides".equals(observationKind)
                        ? "module_provides"
                        : "spi_provider_observation";

        addSpiProviderCandidate(
                observationIndex,
                observation,
                implementation,
                mechanism,
                sourceAttrs,
                resolutionPolicy,
                confidencePolicy,
                candidates,
                warnings
        );
    }

    private static void addSpiLoadCandidate(
            int observationIndex,
            NormalizedObservationFact observation,
            String mechanism,
            Map<String, Object> sourceAttrs,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates,
            List<String> warnings
    ) {
        String siteSymbol =
                trimToNull(observation.siteSymbol());

        if (siteSymbol == null) {
            warnings.add(
                    observation.kind()
                            + " observation has no siteSymbol "
                            + "and was skipped: index="
                            + observationIndex
            );
            return;
        }

        PromotionTarget service =
                target(
                        observation,
                        UNRESOLVED_SERVICE,
                        "service:"
                );

        Map<String, Object> relationAttrs =
                spiBaseAttrs(
                        observation,
                        sourceAttrs
                );

        relationAttrs.put(
                "semantic_kind",
                "spi_service_load"
        );
        relationAttrs.put("mechanism", mechanism);
        relationAttrs.put(
                "service_type",
                service.rawType()
        );

        addSpiCandidate(
                observationIndex,
                observation,
                "loads_service",
                siteSymbol,
                service,
                false,
                relationAttrs,
                resolutionPolicy,
                confidencePolicy,
                candidates
        );
    }

    private static void addSpiProviderCandidate(
            int observationIndex,
            NormalizedObservationFact observation,
            String implementation,
            String mechanism,
            Map<String, Object> sourceAttrs,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates,
            List<String> warnings
    ) {
        String moduleSymbol =
                trimToNull(observation.siteSymbol());

        String implementationType =
                trimToNull(implementation);

        PromotionTarget service =
                target(
                        observation,
                        UNRESOLVED_SERVICE,
                        "service:"
                );

        if (implementationType == null
                && moduleSymbol == null) {
            warnings.add(
                    observation.kind()
                            + " observation has no provider or siteSymbol "
                            + "and was skipped: index="
                            + observationIndex
            );
            return;
        }

        boolean inferredProvider =
                implementationType == null;

        String sourceSymbol =
                inferredProvider
                        ? moduleSymbol
                        : normalizeTypeSymbol(
                                implementationType
                        );

        Map<String, Object> relationAttrs =
                spiBaseAttrs(
                        observation,
                        sourceAttrs
                );

        relationAttrs.put(
                "semantic_kind",
                "spi_provider"
        );
        relationAttrs.put("mechanism", mechanism);
        relationAttrs.put(
                "service_type",
                service.rawType()
        );
        relationAttrs.put(
                "provider_resolution",
                inferredProvider
                        ? "module_fallback"
                        : "explicit_implementation"
        );

        if (implementationType != null) {
            relationAttrs.put(
                    "implementation_type",
                    rawType(
                            implementationType,
                            UNRESOLVED_SERVICE
                    )
            );
        }

        if (moduleSymbol != null) {
            relationAttrs.put(
                    "module_symbol",
                    moduleSymbol
            );
        }

        addSpiCandidate(
                observationIndex,
                observation,
                "provides_spi",
                sourceSymbol,
                service,
                inferredProvider,
                relationAttrs,
                resolutionPolicy,
                confidencePolicy,
                candidates
        );
    }

    private static void addSpiCandidate(
            int observationIndex,
            NormalizedObservationFact observation,
            String relationKind,
            String sourceSymbol,
            PromotionTarget service,
            boolean inferred,
            Map<String, Object> relationAttrs,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<ObservationPromotionShadowCandidate> candidates
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
                        .targetSymbolResolved(
                                service.symbolResolved()
                        )
                        .targetReferenceKnown(
                                service.referenceKnown()
                        )
                        .targetReferenceAuthoritative(
                                service.referenceAuthoritative()
                        )
                        .inferred(inferred)
                        .candidateCount(
                                service.referenceKnown() ? 1 : 0
                        )
                        .evidencePresent(!evidenceIds.isEmpty())
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

        relationAttrs.put(
                "target_resolution",
                policy.status()
        );
        putPolicyAttrs(relationAttrs, policy);

        String dstSymbol =
                service.typeSymbol();

        String dstRawRef =
                dstSymbol == null
                        ? "service:" + service.rawType()
                        : null;

        NormalizedRelationFact relation =
                relation(
                        relationKind,
                        sourceSymbol,
                        dstSymbol,
                        dstRawRef,
                        origin,
                        policy,
                        relationAttrs,
                        evidenceIds
                );

        candidates.add(
                new ObservationPromotionShadowCandidate(
                        observationIndex,
                        normalizeCode(observation.kind()),
                        relation
                )
        );
    }

    private static NormalizedRelationFact relation(
            String kind,
            String srcSymbol,
            String dstSymbol,
            String dstRawRef,
            FactOriginKind origin,
            PolicyResult policy,
            Map<String, Object> attrs,
            List<String> evidenceIds
    ) {
        return new NormalizedRelationFact(
                kind,
                srcSymbol,
                dstSymbol,
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

    private static PromotionTarget target(
            NormalizedObservationFact observation,
            String unresolvedValue,
            String unresolvedPrefix
    ) {
        String targetSymbol =
                trimToNull(
                        observation.targetSymbol()
                );

        if (targetSymbol != null) {
            String typeSymbol =
                    normalizeTypeSymbol(
                            targetSymbol
                    );

            return new PromotionTarget(
                    typeSymbol,
                    rawType(
                            typeSymbol,
                            unresolvedValue
                    ),
                    true,
                    true,
                    true,
                    unresolvedPrefix
            );
        }

        JsonNode typeRef =
                observation.targetTypeRef();

        if (typeRef == null
                || typeRef.isNull()) {
            return PromotionTarget.unknown(
                    unresolvedValue,
                    unresolvedPrefix
            );
        }

        String raw =
                firstNonBlank(
                        text(typeRef, "raw"),
                        firstNonBlank(
                                text(typeRef, "source_text"),
                                text(typeRef, "sourceText")
                        )
                );

        if (raw == null) {
            return PromotionTarget.unknown(
                    unresolvedValue,
                    unresolvedPrefix
            );
        }

        boolean authoritative =
                !booleanNode(
                        typeRef.get("unresolved")
                );

        return new PromotionTarget(
                authoritative
                        ? normalizeTypeSymbol(raw)
                        : null,
                rawType(raw, unresolvedValue),
                false,
                true,
                authoritative,
                unresolvedPrefix
        );
    }

    private static Map<String, Object> spiBaseAttrs(
            NormalizedObservationFact observation,
            Map<String, Object> sourceAttrs
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>(sourceAttrs);

        result.put(
                "resolver",
                "SpiObservationResolver"
        );

        result.put(
                "source_observation_kind",
                normalizeCode(observation.kind())
                        .toUpperCase(Locale.ROOT)
        );

        return result;
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
            case "bytecode" -> FactOriginKind.BYTECODE;
            case "ast_and_bytecode", "merged" ->
                    FactOriginKind.AST_AND_BYTECODE;
            case "resource" -> FactOriginKind.RESOURCE;
            case "observed" -> FactOriginKind.OBSERVED;
            default -> FactOriginKind.OBSERVED;
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

    private static List<String> normalizeHttpMethods(
            List<String> rawMethods
    ) {
        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        for (String rawMethod : rawMethods) {
            String method =
                    trimToNull(rawMethod);

            if (method != null) {
                result.add(
                        method.toUpperCase(Locale.ROOT)
                );
            }
        }

        return List.copyOf(result);
    }

    private static List<String> normalizeHttpPaths(
            List<String> rawPaths
    ) {
        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        for (String rawPath : rawPaths) {
            String path =
                    trimToNull(rawPath);

            if (path != null) {
                result.add(
                        normalizeHttpPath(path)
                );
            }
        }

        return List.copyOf(result);
    }

    private static String normalizeHttpPath(
            String path
    ) {
        String normalized =
                path == null ? "" : path.trim();

        if (normalized.isEmpty()) {
            return "/";
        }

        return normalized.startsWith("/")
                ? normalized
                : "/" + normalized;
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
                collectStrings(item, destination);
            }

            return;
        }

        if (raw.getClass().isArray()) {
            int length =
                    java.lang.reflect.Array.getLength(raw);

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

            return;
        }

        String value =
                trimToNull(String.valueOf(raw));

        if (value != null) {
            destination.add(value);
        }
    }

    private static String firstString(
            Map<String, Object> attrs,
            String... keys
    ) {
        if (attrs == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            Object value = attrs.get(key);

            if (value == null) {
                continue;
            }

            String normalized =
                    trimToNull(String.valueOf(value));

            if (normalized != null) {
                return normalized;
            }
        }

        return null;
    }

    private static boolean booleanValue(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }

        return raw != null
                && Boolean.parseBoolean(
                        String.valueOf(raw)
                );
    }

    private static boolean booleanNode(
            JsonNode node
    ) {
        if (node == null || node.isNull()) {
            return false;
        }

        if (node.isBoolean()) {
            return node.asBoolean();
        }

        return Boolean.parseBoolean(
                node.asText()
        );
    }

    private static String text(
            JsonNode node,
            String field
    ) {
        if (node == null || field == null) {
            return null;
        }

        JsonNode value = node.get(field);

        return value == null || value.isNull()
                ? null
                : trimToNull(value.asText());
    }

    private static String normalizeTypeSymbol(
            String value
    ) {
        String trimmed = value.trim();

        return trimmed.startsWith("type:")
                ? trimmed
                : "type:" + trimmed;
    }

    private static String rawType(
            String value,
            String unresolvedValue
    ) {
        String trimmed =
                value == null
                        ? unresolvedValue
                        : value.trim();

        if (trimmed.startsWith("type:")) {
            return trimmed.substring(
                    "type:".length()
            );
        }

        if (trimmed.startsWith("event:")) {
            return trimmed.substring(
                    "event:".length()
            );
        }

        if (trimmed.startsWith("service:")) {
            return trimmed.substring(
                    "service:".length()
            );
        }

        return trimmed.isBlank()
                ? unresolvedValue
                : trimmed;
    }

    private static List<String> sanitizeEvidenceIds(
            List<String> source
    ) {
        if (source == null || source.isEmpty()) {
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

    private static String normalizeCode(String value) {
        String normalized =
                trimToNull(value);

        return normalized == null
                ? null
                : normalized.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
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

    private record PromotionTarget(
            String typeSymbol,
            String rawType,
            boolean symbolResolved,
            boolean referenceKnown,
            boolean referenceAuthoritative,
            String unresolvedPrefix
    ) {

        private static PromotionTarget unknown(
                String unresolvedValue,
                String unresolvedPrefix
        ) {
            return new PromotionTarget(
                    null,
                    unresolvedValue,
                    false,
                    false,
                    false,
                    unresolvedPrefix
            );
        }
    }
}
