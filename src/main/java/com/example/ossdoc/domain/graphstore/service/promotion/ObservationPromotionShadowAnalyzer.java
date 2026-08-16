package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionContract;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionContractCatalog;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowIssue;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * facts.json에 노출된 Observation과 Extraction resolver가 생성한
 * 의미 Relation을 비교하는 read-only shadow analyzer.
 *
 * <p>이 클래스는 Relation 또는 Edge를 생성하거나 저장하지 않는다.
 * 실제 승격 로직 이전에 계약 차이를 관측하는 용도로만 사용한다.</p>
 */
public final class ObservationPromotionShadowAnalyzer {

    private static final Set<String> VALID_RESOLUTION_STATUSES =
            Set.of(
                    "resolved",
                    "partial",
                    "unresolved"
            );

    private ObservationPromotionShadowAnalyzer() {
    }

    public static ObservationPromotionShadowReport analyze(
            NormalizedFactsDocument facts
    ) {
        if (facts == null) {
            return new ObservationPromotionShadowReport(
                    0,
                    0,
                    List.of()
            );
        }

        List<NormalizedObservationFact> observations =
                safeList(facts.observations());

        RelationAnchorIndex relationIndex =
                RelationAnchorIndex.from(
                        safeList(facts.relations())
                );

        List<ObservationPromotionShadowIssue> issues =
                new ArrayList<>(observations.size());

        int promotableObservations = 0;

        for (int index = 0;
             index < observations.size();
             index++) {

            NormalizedObservationFact observation =
                    observations.get(index);

            String observationKind =
                    observation == null
                            ? null
                            : normalizeCode(observation.kind());

            Optional<ObservationPromotionContract> contract =
                    ObservationPromotionContractCatalog.find(
                            observationKind
                    );

            if (contract.isEmpty()) {
                issues.add(notPromotable(
                        index,
                        observation,
                        observationKind
                ));
                continue;
            }

            promotableObservations++;

            issues.add(analyzePromotable(
                    index,
                    observation,
                    contract.get(),
                    relationIndex
            ));
        }

        return new ObservationPromotionShadowReport(
                observations.size(),
                promotableObservations,
                issues
        );
    }

    private static ObservationPromotionShadowIssue analyzePromotable(
            int observationIndex,
            NormalizedObservationFact observation,
            ObservationPromotionContract contract,
            RelationAnchorIndex relationIndex
    ) {
        ObservationAnchor observationAnchor =
                ObservationAnchor.from(observation);

        List<ScoredRelation> anchoredRelations =
                relationIndex.candidatesFor(
                                observationAnchor,
                                contract
                        )
                        .stream()
                        .map(relation ->
                                new ScoredRelation(
                                        relation.relation(),
                                        anchorScore(
                                                observationAnchor,
                                                contract,
                                                relation
                                        )
                                )
                        )
                        .filter(candidate ->
                                candidate.score() > 0
                        )
                        .sorted(
                                Comparator.comparingInt(
                                                ScoredRelation::score
                                        )
                                        .reversed()
                        )
                        .toList();

        if (anchoredRelations.isEmpty()) {
            return issue(
                    observationIndex,
                    observation,
                    contract.observationKind(),
                    ObservationPromotionShadowStatus
                            .MISSING_RELATION,
                    null,
                    List.of(
                            "No Extraction semantic relation was anchored "
                                    + "to this observation"
                    )
            );
        }

        List<ScoredRelation> allowedKindRelations =
                anchoredRelations.stream()
                        .filter(candidate ->
                                contract.relationKinds()
                                        .contains(
                                                normalizeCode(
                                                        candidate.relation()
                                                                .kind()
                                                )
                                        )
                        )
                        .toList();

        if (allowedKindRelations.isEmpty()) {
            ScoredRelation nearest =
                    anchoredRelations.get(0);

            return issue(
                    observationIndex,
                    observation,
                    contract.observationKind(),
                    ObservationPromotionShadowStatus
                            .KIND_MISMATCH,
                    nearest.relation(),
                    List.of(
                            "Expected one of "
                                    + contract.relationKinds()
                                    + " but found "
                                    + normalizeCode(
                                            nearest.relation().kind()
                                    )
                    )
            );
        }

        ScoredRelation selected =
                allowedKindRelations.get(0);

        NormalizedRelationFact relation =
                selected.relation();

        List<String> metadataReasons =
                validateMetadata(
                        contract,
                        relation
                );

        if (!metadataReasons.isEmpty()) {
            return issue(
                    observationIndex,
                    observation,
                    contract.observationKind(),
                    ObservationPromotionShadowStatus
                            .METADATA_MISMATCH,
                    relation,
                    metadataReasons
            );
        }

        List<String> missingEvidence =
                missingSourceEvidence(
                        observation,
                        relation
                );

        if (!missingEvidence.isEmpty()) {
            return issue(
                    observationIndex,
                    observation,
                    contract.observationKind(),
                    ObservationPromotionShadowStatus
                            .EVIDENCE_MISMATCH,
                    relation,
                    List.of(
                            "Relation is missing source Observation "
                                    + "Evidence IDs: "
                                    + missingEvidence
                    )
            );
        }

        return issue(
                observationIndex,
                observation,
                contract.observationKind(),
                ObservationPromotionShadowStatus.MATCHED,
                relation,
                List.of()
        );
    }

    /**
     * 동일 Observation에서 생성되었을 가능성이 높은 Relation을 찾는다.
     *
     * 우선순위:
     * 1. source_observation_kind
     * 2. Observation Evidence 전체 승계
     * 3. 일부 Evidence 교집합
     * 4. srcSymbol == siteSymbol
     * 5. attrs 내부에 siteSymbol이 명시됨
     *
     * target 일치는 후보 간 선택용 보너스로만 사용한다.
     */
    private static int anchorScore(
            ObservationAnchor observation,
            ObservationPromotionContract contract,
            IndexedRelation relation
    ) {
        if (observation == null || relation == null) {
            return 0;
        }

        int score = 0;

        if (sameObservationKind(
                observation.kind(),
                relation.sourceObservationKind()
        )) {
            score += 100;
        }

        Set<String> observationEvidence =
                observation.evidenceIds();

        Set<String> relationEvidence =
                relation.evidenceIds();

        if (!observationEvidence.isEmpty()) {
            if (relationEvidence.containsAll(
                    observationEvidence
            )) {
                score += 80;
            } else if (!disjoint(
                    observationEvidence,
                    relationEvidence
            )) {
                score += 50;
            }
        }

        String siteSymbol =
                observation.siteSymbol();

        if (siteSymbol != null) {
            if (siteSymbol.equals(
                    relation.srcSymbol()
            )) {
                score += 40;
            }

            if (relation.attrValues().contains(siteSymbol)) {
                score += 35;
            }
        }

        if (observation.target() != null
                && observation.target().equals(
                relation.destination()
        )) {
            score += 20;
        }

        String resolver =
                relation.resolver();

        if (contract.resolverClassName()
                .equals(resolver)) {
            score += 5;
        }

        return score;
    }

    private static List<String> validateMetadata(
            ObservationPromotionContract contract,
            NormalizedRelationFact relation
    ) {
        List<String> reasons =
                new ArrayList<>();

        if (!contract.derivation().equals(
                normalizeCode(relation.derivation())
        )) {
            reasons.add(
                    "derivation expected="
                            + contract.derivation()
                            + ", actual="
                            + relation.derivation()
            );
        }

        String resolver =
                stringAttr(
                        relation.attrs(),
                        "resolver"
                );

        if (!contract.resolverClassName()
                .equals(resolver)) {
            reasons.add(
                    "resolver expected="
                            + contract.resolverClassName()
                            + ", actual="
                            + resolver
            );
        }

        String semanticKind =
                normalizeCode(
                        stringAttr(
                                relation.attrs(),
                                "semantic_kind"
                        )
                );

        if (!contract.semanticKinds()
                .contains(semanticKind)) {
            reasons.add(
                    "semantic_kind expected one of "
                            + contract.semanticKinds()
                            + ", actual="
                            + semanticKind
            );
        }

        for (String requiredAttr
                : contract.requiredRelationAttrs()) {
            if (!hasMeaningfulAttr(
                    relation.attrs(),
                    requiredAttr
            )) {
                reasons.add(
                        "required attr missing: "
                                + requiredAttr
                );
            }
        }

        String resolutionStatus =
                normalizeCode(
                        relation.resolutionStatus()
                );

        if (!VALID_RESOLUTION_STATUSES
                .contains(resolutionStatus)) {
            reasons.add(
                    "resolution status is missing or unsupported: "
                            + relation.resolutionStatus()
            );
        }

        BigDecimal confidence =
                relation.confidenceHint();

        if (confidence == null) {
            reasons.add("confidence_hint is missing");
        } else if (confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) {
            reasons.add(
                    "confidence_hint is outside [0,1]: "
                            + confidence
            );
        }

        if (trimToNull(relation.dstSymbol()) == null
                && trimToNull(relation.dstRawRef()) == null) {
            reasons.add("relation destination is missing");
        }

        return List.copyOf(reasons);
    }

    /**
     * DI는 provider Evidence가 추가될 수 있지만,
     * 원본 injection Observation Evidence는 반드시 포함되어야 한다.
     * 다른 정책도 동일하게 source Observation Evidence의 부분집합 조건을 검증한다.
     */
    private static List<String> missingSourceEvidence(
            NormalizedObservationFact observation,
            NormalizedRelationFact relation
    ) {
        Set<String> sourceEvidence =
                normalizedIds(
                        observation == null
                                ? null
                                : observation.evidenceIds()
                );

        if (sourceEvidence.isEmpty()) {
            return List.of();
        }

        Set<String> relationEvidence =
                normalizedIds(
                        relation == null
                                ? null
                                : relation.evidenceIds()
                );

        return sourceEvidence.stream()
                .filter(id ->
                        !relationEvidence.contains(id)
                )
                .toList();
    }

    private static ObservationPromotionShadowIssue notPromotable(
            int index,
            NormalizedObservationFact observation,
            String observationKind
    ) {
        return issue(
                index,
                observation,
                observationKind,
                ObservationPromotionShadowStatus.NOT_PROMOTABLE,
                null,
                List.of(
                        "No Observation promotion contract is registered"
                )
        );
    }

    private static ObservationPromotionShadowIssue issue(
            int index,
            NormalizedObservationFact observation,
            String observationKind,
            ObservationPromotionShadowStatus status,
            NormalizedRelationFact relation,
            List<String> reasons
    ) {
        return new ObservationPromotionShadowIssue(
                index,
                observationKind,
                observation == null
                        ? null
                        : observation.siteSymbol(),
                observationTarget(observation),
                status,
                relation == null
                        ? null
                        : normalizeCode(relation.kind()),
                relation == null
                        ? null
                        : relation.srcSymbol(),
                relationDestination(relation),
                reasons
        );
    }

    private static String observationTarget(
            NormalizedObservationFact observation
    ) {
        if (observation == null) {
            return null;
        }

        String targetSymbol =
                trimToNull(
                        observation.targetSymbol()
                );

        if (targetSymbol != null) {
            return targetSymbol;
        }

        JsonNode targetTypeRef =
                observation.targetTypeRef();

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

        if (raw != null && raw.isTextual()) {
            return trimToNull(raw.asText());
        }

        JsonNode sourceText =
                targetTypeRef.get("source_text");

        if (sourceText != null
                && sourceText.isTextual()) {
            return trimToNull(
                    sourceText.asText()
            );
        }

        return null;
    }

    private static String relationDestination(
            NormalizedRelationFact relation
    ) {
        if (relation == null) {
            return null;
        }

        String destinationSymbol =
                trimToNull(relation.dstSymbol());

        return destinationSymbol != null
                ? destinationSymbol
                : trimToNull(relation.dstRawRef());
    }

    private static String normalizeTarget(String value) {
        String normalized =
                trimToNull(value);

        if (normalized == null) {
            return null;
        }

        String lower =
                normalized.toLowerCase(Locale.ROOT);

        for (String prefix : List.of(
                "type:",
                "event:",
                "service:",
                "bean:"
        )) {
            if (lower.startsWith(prefix)) {
                normalized = normalized.substring(
                        prefix.length()
                );
                break;
            }
        }

        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 6
            );
        }

        return normalized.trim();
    }

    private static boolean sameObservationKind(
            String observationKind,
            String attrValue
    ) {
        String left =
                normalizeCode(observationKind);

        String right =
                normalizeCode(attrValue);

        return left != null && left.equals(right);
    }

    private static boolean hasMeaningfulAttr(
            Map<String, Object> attrs,
            String key
    ) {
        if (attrs == null
                || key == null
                || !attrs.containsKey(key)) {
            return false;
        }

        Object value = attrs.get(key);

        if (value == null) {
            return false;
        }

        if (value instanceof CharSequence sequence) {
            return !sequence.toString().isBlank();
        }

        return true;
    }

    private static String stringAttr(
            Map<String, Object> attrs,
            String key
    ) {
        if (attrs == null || key == null) {
            return null;
        }

        Object value = attrs.get(key);

        return value == null
                ? null
                : trimToNull(
                        String.valueOf(value)
                );
    }

    private static Set<String> normalizedIds(
            List<String> ids
    ) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();

        for (String id : ids) {
            String value = trimToNull(id);

            if (value != null) {
                normalized.add(value);
            }
        }

        return Set.copyOf(normalized);
    }

    private static boolean disjoint(
            Set<String> left,
            Set<String> right
    ) {
        for (String value : left) {
            if (right.contains(value)) {
                return false;
            }
        }

        return true;
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

    private static <T> List<T> safeList(
            List<T> values
    ) {
        return values == null
                ? List.of()
                : values;
    }

    /**
     * Observation promotion shadow는 원래 observation마다 모든 relation을 다시 훑었다.
     * relation anchor 조건에 쓰이는 값을 한 번만 인덱싱해 점수가 생길 수 있는 후보만 평가한다.
     */
    private static final class RelationAnchorIndex {

        private final Map<String, List<IndexedRelation>> bySourceObservationKind;
        private final Map<String, List<IndexedRelation>> byEvidenceId;
        private final Map<String, List<IndexedRelation>> bySrcSymbol;
        private final Map<String, List<IndexedRelation>> byAttrValue;
        private final Map<String, List<IndexedRelation>> byDestination;
        private final Map<String, List<IndexedRelation>> byResolver;

        private RelationAnchorIndex(
                Map<String, List<IndexedRelation>> bySourceObservationKind,
                Map<String, List<IndexedRelation>> byEvidenceId,
                Map<String, List<IndexedRelation>> bySrcSymbol,
                Map<String, List<IndexedRelation>> byAttrValue,
                Map<String, List<IndexedRelation>> byDestination,
                Map<String, List<IndexedRelation>> byResolver
        ) {
            this.bySourceObservationKind = bySourceObservationKind;
            this.byEvidenceId = byEvidenceId;
            this.bySrcSymbol = bySrcSymbol;
            this.byAttrValue = byAttrValue;
            this.byDestination = byDestination;
            this.byResolver = byResolver;
        }

        static RelationAnchorIndex from(List<NormalizedRelationFact> relations) {
            List<IndexedRelation> indexed = new ArrayList<>();
            Map<String, List<IndexedRelation>> bySourceObservationKind = new LinkedHashMap<>();
            Map<String, List<IndexedRelation>> byEvidenceId = new LinkedHashMap<>();
            Map<String, List<IndexedRelation>> bySrcSymbol = new LinkedHashMap<>();
            Map<String, List<IndexedRelation>> byAttrValue = new LinkedHashMap<>();
            Map<String, List<IndexedRelation>> byDestination = new LinkedHashMap<>();
            Map<String, List<IndexedRelation>> byResolver = new LinkedHashMap<>();

            for (int index = 0; index < relations.size(); index++) {
                NormalizedRelationFact relation = relations.get(index);
                if (relation == null) {
                    continue;
                }

                IndexedRelation value = IndexedRelation.from(index, relation);
                indexed.add(value);
                add(bySourceObservationKind, value.sourceObservationKind(), value);
                add(bySrcSymbol, value.srcSymbol(), value);
                add(byDestination, value.destination(), value);
                add(byResolver, value.resolver(), value);

                for (String evidenceId : value.evidenceIds()) {
                    add(byEvidenceId, evidenceId, value);
                }
                for (String attrValue : value.attrValues()) {
                    add(byAttrValue, attrValue, value);
                }
            }

            return new RelationAnchorIndex(
                    freeze(bySourceObservationKind),
                    freeze(byEvidenceId),
                    freeze(bySrcSymbol),
                    freeze(byAttrValue),
                    freeze(byDestination),
                    freeze(byResolver)
            );
        }

        List<IndexedRelation> candidatesFor(
                ObservationAnchor observation,
                ObservationPromotionContract contract
        ) {
            if (observation == null) {
                return List.of();
            }

            Map<Integer, IndexedRelation> candidates = new LinkedHashMap<>();
            addAll(candidates, indexed(bySourceObservationKind, observation.kind()));
            addAll(candidates, indexed(bySrcSymbol, observation.siteSymbol()));
            addAll(candidates, indexed(byAttrValue, observation.siteSymbol()));
            addAll(candidates, indexed(byDestination, observation.target()));

            if (contract != null) {
                addAll(candidates, indexed(byResolver, contract.resolverClassName()));
            }

            for (String evidenceId : observation.evidenceIds()) {
                addAll(candidates, indexed(byEvidenceId, evidenceId));
            }

            if (candidates.isEmpty()) {
                return List.of();
            }

            return candidates.values()
                    .stream()
                    .sorted(Comparator.comparingInt(IndexedRelation::index))
                    .toList();
        }

        private static void add(
                Map<String, List<IndexedRelation>> index,
                String key,
                IndexedRelation relation
        ) {
            if (key == null || relation == null) {
                return;
            }
            index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(relation);
        }

        private static void addAll(
                Map<Integer, IndexedRelation> target,
                List<IndexedRelation> values
        ) {
            if (values == null || values.isEmpty()) {
                return;
            }
            for (IndexedRelation value : values) {
                target.putIfAbsent(value.index(), value);
            }
        }

        private static List<IndexedRelation> indexed(
                Map<String, List<IndexedRelation>> index,
                String key
        ) {
            if (key == null || index == null) {
                return List.of();
            }
            return index.getOrDefault(key, List.of());
        }

        private static Map<String, List<IndexedRelation>> freeze(
                Map<String, List<IndexedRelation>> source
        ) {
            source.replaceAll((key, values) -> List.copyOf(values));
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }

    private record ObservationAnchor(
            String kind,
            String siteSymbol,
            String target,
            Set<String> evidenceIds
    ) {

        static ObservationAnchor from(NormalizedObservationFact observation) {
            if (observation == null) {
                return new ObservationAnchor(null, null, null, Set.of());
            }
            return new ObservationAnchor(
                    normalizeCode(observation.kind()),
                    trimToNull(observation.siteSymbol()),
                    normalizeTarget(observationTarget(observation)),
                    normalizedIds(observation.evidenceIds())
            );
        }
    }

    private record IndexedRelation(
            int index,
            NormalizedRelationFact relation,
            String sourceObservationKind,
            Set<String> evidenceIds,
            String srcSymbol,
            Set<String> attrValues,
            String destination,
            String resolver
    ) {

        static IndexedRelation from(int index, NormalizedRelationFact relation) {
            return new IndexedRelation(
                    index,
                    relation,
                    normalizeCode(stringAttr(relation.attrs(), "source_observation_kind")),
                    normalizedIds(relation.evidenceIds()),
                    trimToNull(relation.srcSymbol()),
                    scalarAttrValues(relation.attrs()),
                    normalizeTarget(relationDestination(relation)),
                    stringAttr(relation.attrs(), "resolver")
            );
        }
    }

    private static Set<String> scalarAttrValues(Object value) {
        if (value == null) {
            return Set.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectScalarAttrValues(value, result);
        return Set.copyOf(result);
    }

    private static void collectScalarAttrValues(Object value, Set<String> target) {
        if (value == null) {
            return;
        }

        if (value instanceof CharSequence sequence) {
            String normalized = trimToNull(sequence.toString());
            if (normalized != null) {
                target.add(normalized);
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                collectScalarAttrValues(nested, target);
            }
            return;
        }

        if (value instanceof Collection<?> collection) {
            for (Object nested : collection) {
                collectScalarAttrValues(nested, target);
            }
            return;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectScalarAttrValues(Array.get(value, index), target);
            }
            return;
        }

        target.add(String.valueOf(value));
    }

    private record ScoredRelation(
            NormalizedRelationFact relation,
            int score
    ) {
    }
}
