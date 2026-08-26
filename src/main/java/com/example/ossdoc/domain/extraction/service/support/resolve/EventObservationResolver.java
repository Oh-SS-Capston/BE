package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** EVENT_PUBLICATION / EVENT_SUBSCRIPTION을 event 의미 관계로 승격한다. */
@Component
public class EventObservationResolver implements ObservationRelationResolver {

    private static final String UNRESOLVED_EVENT = "<unresolved-event>";

    private RelationResolutionPolicy resolutionPolicy;
    private RelationConfidencePolicy confidencePolicy;

    public EventObservationResolver() {
        this.resolutionPolicy = new RelationResolutionPolicy();
        this.confidencePolicy = new RelationConfidencePolicy();
    }

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
    public ObservationResolutionResult resolve(
            ObservationResolutionContext context
    ) {
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
                warnings.add(
                        observation.kind()
                                + " observation has no siteSymbol and was skipped"
                );
                continue;
            }

            relations.add(buildRelation(
                    observation,
                    relationKind,
                    semanticKind,
                    siteSymbol,
                    eventTarget(observation)
            ));
        }
    }

    private RelationFact buildRelation(
            ObservationFact observation,
            RelationKind relationKind,
            String semanticKind,
            String siteSymbol,
            EventTarget target
    ) {
        List<String> evidenceIds = sanitizeEvidenceIds(
                observation.evidenceIds()
        );
        FactOriginKind origin = observation.origin() == null
                ? FactOriginKind.OBSERVED
                : observation.origin();

        RelationPolicyInput policyInput = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(target.symbolResolved())
                .targetReferenceKnown(target.referenceKnown())
                .targetReferenceAuthoritative(
                        target.referenceAuthoritative()
                )
                .inferred(false)
                .candidateCount(target.referenceKnown() ? 1 : 0)
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(observation.confidenceHint())
                .build();

        ResolutionAssessment resolution = resolutionPolicy.assess(policyInput);
        ConfidenceAssessment confidence = confidencePolicy.assess(
                policyInput,
                resolution
        );

        Map<String, Object> attrs = copyNonNullAttrs(observation.attrs());
        attrs.put("semantic_kind", semanticKind);
        attrs.put("resolver", getClass().getSimpleName());
        attrs.put("event_type", target.rawType());
        attrs.put(
                "target_resolution",
                resolution.status().name().toLowerCase(Locale.ROOT)
        );
        putPolicyAttrs(attrs, resolution, confidence);

        RelationFact.RelationFactBuilder builder = RelationFact.builder()
                .kind(relationKind)
                .srcSymbol(siteSymbol)
                .evidenceIds(evidenceIds)
                .resolution(resolution.toRelationResolution())
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence.value())
                .attrs(immutableAttrs(attrs));

        if (target.typeSymbol() != null) {
            builder.dstSymbol(target.typeSymbol());
        } else {
            builder.dstRawRef("event:" + target.rawType());
        }

        return builder.build();
    }

    private EventTarget eventTarget(ObservationFact observation) {
        String targetSymbol = trimToNull(observation.targetSymbol());
        if (targetSymbol != null) {
            String typeSymbol = normalizeTypeSymbol(targetSymbol);
            return new EventTarget(
                    typeSymbol,
                    rawType(typeSymbol),
                    true,
                    true,
                    true
            );
        }

        TypeRef typeRef = observation.targetTypeRef();
        if (typeRef == null) {
            return EventTarget.unknown();
        }

        String raw = firstNonBlank(typeRef.raw(), typeRef.sourceText());
        if (raw == null) {
            return EventTarget.unknown();
        }

        boolean authoritative = !Boolean.TRUE.equals(typeRef.unresolved());
        return new EventTarget(
                authoritative ? normalizeTypeSymbol(raw) : null,
                rawType(raw),
                false,
                true,
                authoritative
        );
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

    private String normalizeTypeSymbol(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("type:")
                ? trimmed
                : "type:" + trimmed;
    }

    private String rawType(String value) {
        String trimmed = value == null
                ? UNRESOLVED_EVENT
                : value.trim();
        if (trimmed.startsWith("type:")) {
            return trimmed.substring("type:".length());
        }
        if (trimmed.startsWith("event:")) {
            return trimmed.substring("event:".length());
        }
        return trimmed.isBlank() ? UNRESOLVED_EVENT : trimmed;
    }

    private Map<String, Object> copyNonNullAttrs(
            Map<String, Object> source
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> immutableAttrs(
            Map<String, Object> attrs
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
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
            boolean symbolResolved,
            boolean referenceKnown,
            boolean referenceAuthoritative
    ) {
        private static EventTarget unknown() {
            return new EventTarget(
                    null,
                    UNRESOLVED_EVENT,
                    false,
                    false,
                    false
            );
        }
    }
}
