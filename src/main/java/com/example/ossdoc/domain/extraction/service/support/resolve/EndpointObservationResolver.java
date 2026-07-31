package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.service.support.util.RelationResolutionFactory;
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

/**
 * HTTP_ENDPOINT observation을 HANDLES_ENDPOINT 의미 관계로 승격한다.
 *
 * <p>하나의 observation에 복수 HTTP method/path가 있으면 가능한 조합마다
 * 별도 relation을 생성한다. path를 정적으로 해석하지 못한 경우에도
 * observation을 버리지 않고 PARTIAL relation으로 남긴다.</p>
 */
@Component
public class EndpointObservationResolver
        implements ObservationRelationResolver {

    private static final String UNRESOLVED_PATH = "<unresolved-path>";
    private static final String CONFLICTING_METHOD = "<conflicting-method>";

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

        return new ObservationResolutionResult(
                relations,
                warnings
        );
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
            warnings.add(
                    "HTTP_ENDPOINT observation has no siteSymbol and was skipped"
            );
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
                Objects.toString(
                        observationAttrs.get("path_resolution"),
                        ""
                )
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
                        "HTTP endpoint method mapping conflict",
                        0.4
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
                        "HTTP endpoint path could not be resolved",
                        0.6
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
                        null,
                        0.9
                ));
            }
        }
    }

    private RelationFact buildRelation(
            ObservationFact endpoint,
            String httpMethod,
            String path,
            boolean resolved,
            String partialReason,
            double fallbackConfidence
    ) {
        String normalizedMethod = trimToNull(httpMethod) == null
                ? "ANY"
                : httpMethod.trim().toUpperCase(Locale.ROOT);

        String normalizedPath = UNRESOLVED_PATH.equals(path)
                ? UNRESOLVED_PATH
                : normalizeHttpPath(path);

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (endpoint.attrs() != null) {
            attrs.putAll(endpoint.attrs());
        }
        attrs.put("http_method", normalizedMethod);
        attrs.put("path", normalizedPath);
        attrs.put("semantic_kind", "http_endpoint");
        attrs.put("resolver", getClass().getSimpleName());

        double confidence = endpoint.confidenceHint() == null
                ? fallbackConfidence
                : resolved
                ? endpoint.confidenceHint()
                : Math.min(endpoint.confidenceHint(), fallbackConfidence);

        return RelationFact.builder()
                .kind(RelationKind.HANDLES_ENDPOINT)
                .srcSymbol(endpoint.siteSymbol())
                .dstRawRef(normalizedMethod + " " + normalizedPath)
                .evidenceIds(sanitizeEvidenceIds(endpoint.evidenceIds()))
                .resolution(
                        resolved
                                ? RelationResolutionFactory.resolved()
                                : RelationResolutionFactory.partial(partialReason)
                )
                .origin(endpoint.origin() == null
                        ? FactOriginKind.OBSERVED
                        : endpoint.origin())
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence)
                .attrs(Map.copyOf(attrs))
                .build();
    }

    private List<String> normalizeHttpMethods(List<String> rawMethods) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();

        for (String rawMethod : rawMethods) {
            String method = trimToNull(rawMethod);
            if (method == null) {
                continue;
            }
            normalized.add(method.toUpperCase(Locale.ROOT));
        }

        return List.copyOf(normalized);
    }

    private List<String> normalizeHttpPaths(List<String> rawPaths) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();

        for (String rawPath : rawPaths) {
            String path = trimToNull(rawPath);
            if (path == null) {
                continue;
            }
            normalized.add(normalizeHttpPath(path));
        }

        return List.copyOf(normalized);
    }

    private String normalizeHttpPath(String path) {
        String normalized = path == null ? "" : path.trim();

        if (normalized.isEmpty()) {
            return "/";
        }

        if (!normalized.startsWith("/")) {
            return "/" + normalized;
        }

        return normalized;
    }

    private List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectStrings(raw, result);
        return List.copyOf(result);
    }

    private void collectStrings(
            Object raw,
            Collection<String> destination
    ) {
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
        return raw != null
                && Boolean.parseBoolean(String.valueOf(raw));
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
