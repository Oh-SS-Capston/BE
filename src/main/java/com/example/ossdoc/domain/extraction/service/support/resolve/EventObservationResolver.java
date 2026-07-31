package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.service.support.util.RelationResolutionFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** EVENT_PUBLICATION / EVENT_SUBSCRIPTION을 event 의미 관계로 승격한다. */
@Component
public class EventObservationResolver implements ObservationRelationResolver {

    private static final String UNRESOLVED_EVENT = "<unresolved-event>";

    @Override
    public Set<ObservationKind> supportedKinds() {
        return Set.of(
                ObservationKind.EVENT_PUBLICATION,
                ObservationKind.EVENT_SUBSCRIPTION
        );
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public ObservationResolutionResult resolve(ObservationResolutionContext context) {
        if (context == null) {
            return new ObservationResolutionResult(
                    List.of(),
                    List.of("Event resolver received a null context")
            );
        }

        ObservationTable table = context.observations();
        if (table == null) {
            return ObservationResolutionResult.empty();
        }

        List<RelationFact> relations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        resolveAll(
                table.eventPublications(),
                RelationKind.PUBLISHES_EVENT,
                "event_publication",
                relations,
                warnings
        );
        resolveAll(
                table.eventSubscriptions(),
                RelationKind.LISTENS_EVENT,
                "event_subscription",
                relations,
                warnings
        );

        return new ObservationResolutionResult(relations, warnings);
    }

    private void resolveAll(
            List<ObservationFact> observations,
            RelationKind relationKind,
            String semanticKind,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observations == null) {
            return;
        }

        for (ObservationFact observation : observations) {
            if (observation == null) {
                continue;
            }

            String siteSymbol = trimToNull(observation.siteSymbol());
            if (siteSymbol == null) {
                warnings.add(observation.kind() + " observation has no siteSymbol and was skipped");
                continue;
            }

            EventTarget target = eventTarget(observation);
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (observation.attrs() != null) {
                attrs.putAll(observation.attrs());
            }
            attrs.put("semantic_kind", semanticKind);
            attrs.put("resolver", getClass().getSimpleName());
            attrs.put("event_type", target.rawType());
            attrs.put("target_resolution", target.resolved() ? "resolved" : "partial");

            double fallbackConfidence = target.resolved() ? 0.9 : 0.4;
            double confidence = observation.confidenceHint() == null
                    ? fallbackConfidence
                    : target.resolved()
                    ? observation.confidenceHint()
                    : Math.min(observation.confidenceHint(), fallbackConfidence);

            RelationFact.RelationFactBuilder builder = RelationFact.builder()
                    .kind(relationKind)
                    .srcSymbol(siteSymbol)
                    .evidenceIds(sanitizeEvidenceIds(observation.evidenceIds()))
                    .resolution(target.resolved()
                            ? RelationResolutionFactory.resolved()
                            : RelationResolutionFactory.partial(
                            "Event type could not be fully resolved"
                    ))
                    .origin(observation.origin() == null
                            ? FactOriginKind.OBSERVED
                            : observation.origin())
                    .derivation(DerivationKind.DERIVED)
                    .confidenceHint(confidence)
                    .attrs(Map.copyOf(attrs));

            if (target.resolved()) {
                builder.dstSymbol(target.typeSymbol());
            } else {
                builder.dstRawRef("event:" + target.rawType());
            }

            relations.add(builder.build());
        }
    }

    private EventTarget eventTarget(ObservationFact observation) {
        String targetSymbol = trimToNull(observation.targetSymbol());
        if (targetSymbol != null) {
            String typeSymbol = normalizeTypeSymbol(targetSymbol);
            return new EventTarget(typeSymbol, rawType(typeSymbol), true);
        }

        TypeRef typeRef = observation.targetTypeRef();
        if (typeRef == null) {
            return new EventTarget(null, UNRESOLVED_EVENT, false);
        }

        String raw = firstNonBlank(typeRef.raw(), typeRef.sourceText());
        if (raw == null) {
            return new EventTarget(null, UNRESOLVED_EVENT, false);
        }

        boolean resolved = !Boolean.TRUE.equals(typeRef.unresolved());
        return new EventTarget(
                resolved ? normalizeTypeSymbol(raw) : null,
                rawType(raw),
                resolved
        );
    }

    private String normalizeTypeSymbol(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("type:") ? trimmed : "type:" + trimmed;
    }

    private String rawType(String value) {
        String trimmed = value == null ? UNRESOLVED_EVENT : value.trim();
        if (trimmed.startsWith("type:")) {
            return trimmed.substring("type:".length());
        }
        if (trimmed.startsWith("event:")) {
            return trimmed.substring("event:".length());
        }
        return trimmed.isBlank() ? UNRESOLVED_EVENT : trimmed;
    }

    private List<String> sanitizeEvidenceIds(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : source) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private String firstNonBlank(String first, String second) {
        String normalized = trimToNull(first);
        return normalized != null ? normalized : trimToNull(second);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record EventTarget(
            String typeSymbol,
            String rawType,
            boolean resolved
    ) {
    }
}
