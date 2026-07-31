package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.service.support.policy.ConfidenceAssessment;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationPolicyInput;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.ResolutionAssessment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** HTTP_ENDPOINT observation을 HANDLES_ENDPOINT 의미 관계로 승격한다. */
@Component
public class EndpointObservationResolver
        implements ObservationRelationResolver {

    private static final String UNRESOLVED_PATH = "<unresolved-path>";
    private static final String CONFLICTING_METHOD = "<conflicting-method>";

    private RelationResolutionPolicy resolutionPolicy;
    private RelationConfidencePolicy confidencePolicy;

    public EndpointObservationResolver() {
        this.resolutionPolicy = new RelationResolutionPolicy();
        this.confidencePolicy = new RelationConfidencePolicy();
    }

    /** 일반 애플리케이션에서는 Spring 공통 정책 Bean을 사용하고, 단위 테스트는 기본 정책으로 동작한다. */
    @Autowired(required = false)
    void configurePolicies(
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy
    ) {
        if (resolutionPolicy != null) {
            this.resolutionPolicy = resolutionPolicy;
        }
        if (confidencePolicy != null) {
            this.confidencePolicy = confidencePolicy;
        }
    }

    @Override
    public Set<ObservationKind> supportedKinds() {
        return Set.of(ObservationKind.HTTP_ENDPOINT);
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public ObservationResolutionResult resolve(
            ObservationResolutionContext context
    ) {
        if (context == null) {
            return new ObservationResolutionResult(
                    List.of(),
                    List.of("Endpoint resolver received a null context")
            );
        }

        ObservationTable observationTable = context.observations();
        List<ObservationFact> endpoints = observationTable == null
                || observationTable.httpEndpoints() == null
                ? List.of()
                : observationTable.httpEndpoints();

        if (endpoints.isEmpty()) {
            return ObservationResolutionResult.empty();
        }

        List<RelationFact> relations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ObservationFact endpoint : endpoints) {
            resolveEndpoint(endpoint, relations, warnings);
        }

        return new ObservationResolutionResult(relations, warnings);
    }

    private void resolveEndpoint(
            ObservationFact endpoint,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (endpoint == null) {
            return;
        }

        String siteSymbol = trimToNull(endpoint.siteSymbol());
        if (siteSymbol == null) {
            warnings.add("HTTP_ENDPOINT observation has no siteSymbol and was skipped");
            return;
        }

        Map<String, Object> observationAttrs = endpoint.attrs() == null
                ? Map.of()
                : endpoint.attrs();

        List<String> methods = normalizeHttpMethods(
                stringList(observationAttrs.get("http_methods"))
        );
        List<String> paths = normalizeHttpPaths(
                stringList(observationAttrs.get("paths"))
        );

        boolean mappingConflict = booleanValue(
                observationAttrs.get("mapping_conflict")
        );
        boolean unresolvedPath = "unresolved".equalsIgnoreCase(
                Objects.toString(observationAttrs.get("path_resolution"), "")
        ) || paths.isEmpty();

        if (mappingConflict) {
            List<String> conflictPaths = paths.isEmpty()
                    ? List.of(UNRESOLVED_PATH)
                    : paths;

            for (String path : conflictPaths) {
                relations.add(buildRelation(
                        endpoint,
                        CONFLICTING_METHOD,
                        path,
                        false,
                        true,
                        2,
                        "HTTP endpoint method mapping conflict"
                ));
            }
            return;
        }

        if (methods.isEmpty()) {
            methods = List.of("ANY");
        }

        if (unresolvedPath) {
            for (String method : methods) {
                relations.add(buildRelation(
                        endpoint,
                        method,
                        UNRESOLVED_PATH,
                        false,
                        true,
                        1,
                        "HTTP endpoint path could not be resolved"
                ));
            }
            return;
        }

        for (String method : methods) {
            for (String path : paths) {
                relations.add(buildRelation(
                        endpoint,
                        method,
                        path,
                        true,
                        true,
                        1,
                        null
                ));
            }
        }
    }

    private RelationFact buildRelation(
            ObservationFact endpoint,
            String httpMethod,
            String path,
            boolean authoritativeReference,
            boolean referenceKnown,
            int candidateCount,
            String reasonOverride
    ) {
        String normalizedMethod = trimToNull(httpMethod) == null
                ? "ANY"
                : httpMethod.trim().toUpperCase(Locale.ROOT);
        String normalizedPath = UNRESOLVED_PATH.equals(path)
                ? UNRESOLVED_PATH
                : normalizeHttpPath(path);

        List<String> evidenceIds = sanitizeEvidenceIds(endpoint.evidenceIds());
        FactOriginKind origin = endpoint.origin() == null
                ? FactOriginKind.OBSERVED
                : endpoint.origin();

        RelationPolicyInput policyInput = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetReferenceKnown(referenceKnown)
                .targetReferenceAuthoritative(authoritativeReference)
                .candidateCount(candidateCount)
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(endpoint.confidenceHint())
                .build();

        ResolutionAssessment resolution = resolutionPolicy.assess(policyInput);
        if (reasonOverride != null) {
            resolution = new ResolutionAssessment(
                    resolution.status(),
                    resolution.basis(),
                    reasonOverride
            );
        }
        ConfidenceAssessment confidence = confidencePolicy.assess(
                policyInput,
                resolution
        );

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (endpoint.attrs() != null) {
            attrs.putAll(endpoint.attrs());
        }
        attrs.put("http_method", normalizedMethod);
        attrs.put("path", normalizedPath);
        attrs.put("semantic_kind", "http_endpoint");
        attrs.put("resolver", getClass().getSimpleName());
        putPolicyAttrs(attrs, resolution, confidence);

        return RelationFact.builder()
                .kind(RelationKind.HANDLES_ENDPOINT)
                .srcSymbol(endpoint.siteSymbol())
                .dstRawRef(normalizedMethod + " " + normalizedPath)
                .evidenceIds(evidenceIds)
                .resolution(resolution.toRelationResolution())
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence.value())
                .attrs(Map.copyOf(attrs))
                .build();
    }

    private void putPolicyAttrs(
            Map<String, Object> attrs,
            ResolutionAssessment resolution,
            ConfidenceAssessment confidence
    ) {
        attrs.put("resolution_basis", resolution.basis().code());
        attrs.put("confidence_band", confidence.band().code());
        attrs.put("default_visible", confidence.defaultVisible());
    }

    private List<String> normalizeHttpMethods(List<String> rawMethods) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawMethod : rawMethods) {
            String method = trimToNull(rawMethod);
            if (method != null) {
                normalized.add(method.toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeHttpPaths(List<String> rawPaths) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawPath : rawPaths) {
            String path = trimToNull(rawPath);
            if (path != null) {
                normalized.add(normalizeHttpPath(path));
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeHttpPath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.isEmpty()) {
            return "/";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectStrings(raw, result);
        return List.copyOf(result);
    }

    private void collectStrings(Object raw, Collection<String> destination) {
        if (raw == null) {
            return;
        }
        if (raw instanceof CharSequence sequence) {
            String value = trimToNull(sequence.toString());
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
            int length = Array.getLength(raw);
            for (int index = 0; index < length; index++) {
                collectStrings(Array.get(raw, index), destination);
            }
            return;
        }
        String value = trimToNull(String.valueOf(raw));
        if (value != null) {
            destination.add(value);
        }
    }

    private boolean booleanValue(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private List<String> sanitizeEvidenceIds(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String evidenceId : source) {
            String normalized = trimToNull(evidenceId);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
