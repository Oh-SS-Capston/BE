package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.EvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleCandidateSource;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import com.example.ossdoc.domain.rule.exception.RuleCandidateException;
import com.example.ossdoc.domain.rule.exception.code.RuleCandidateErrorCode;
import com.example.ossdoc.domain.rule.repository.RuleCandidateEvidenceRepository;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.rule.repository.RuleMiningSignalRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleMiningSignalIngestService {

    private static final BigDecimal GRAPHSTORE_CONFIDENCE = new BigDecimal("0.8500");
    private static final BigDecimal SNIPPET_CONFIDENCE = new BigDecimal("0.5500");
    private static final BigDecimal CLASSIFIED_CALL_CONFIDENCE = new BigDecimal("0.7500");

    private static final Pattern IF_PATTERN = Pattern.compile("\\bif\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern THROW_PATTERN = Pattern.compile("\\bthrow\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETURN_PATTERN = Pattern.compile("\\breturn\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_RESPONSE_PATTERN = Pattern.compile(
            "\\b(ErrorResponse|ResponseEntity\\.status|ResponseEntity\\.badRequest|ResponseEntity\\.notFound|\\.error\\s*\\()",
            Pattern.CASE_INSENSITIVE
    );

    private final RepoRunRepository repoRunRepository;

    private final EdgeRepository edgeRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final EvidenceRepository evidenceRepository;
    private final SymbolRepository symbolRepository;

    private final RuleMiningSignalRepository ruleMiningSignalRepository;
    private final RuleCandidateRepository ruleCandidateRepository;
    private final RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository;

    @Transactional
    public RuleMiningSignalIngestResult ingest(String runId, boolean forceRebuild) {
        if (runId == null || runId.isBlank()) {
            throw new RuleCandidateException(RuleCandidateErrorCode.INVALID_RULE_MINE_REQUEST);
        }

        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new RuleCandidateException(RuleCandidateErrorCode.RUN_NOT_FOUND));

        boolean alreadyIngested = ruleMiningSignalRepository.existsByRun_RunId(runId);
        if (alreadyIngested && !forceRebuild) {
            long existingCount = ruleMiningSignalRepository.countByRun_RunId(runId);
            return RuleMiningSignalIngestResult.skipped(runId, Math.toIntExact(existingCount));
        }

        if (forceRebuild) {
            deletePreviousMiningResults(runId);
        }

        List<Edge> signalSourceEdges = edgeRepository.findAllByRun_RunId(runId)
                .stream()
                .filter(this::isSignalSourceEdge)
                .toList();

        List<Evidence> evidences = evidenceRepository.findAllByRun_RunId(runId);

        List<SymbolEntity> methodSymbols = symbolRepository.findAllByRun_RunId(runId)
                .stream()
                .filter(this::isMethodLikeSymbol)
                .filter(this::hasSourceLocation)
                .toList();

        if (signalSourceEdges.isEmpty() && evidences.isEmpty() && methodSymbols.isEmpty()) {
            throw new RuleCandidateException(RuleCandidateErrorCode.GRAPHSTORE_DATA_NOT_FOUND);
        }

        Map<Long, List<Evidence>> evidenceByEdgeId = loadEvidenceByEdgeId(signalSourceEdges);
        List<RuleMiningSignal> signals = new ArrayList<>();
        Set<String> signalKeys = new HashSet<>();

        ingestEdgeSignals(run, signalSourceEdges, evidenceByEdgeId, signals, signalKeys);
        ingestSnippetSignals(run, evidences, methodSymbols, signals, signalKeys);

        if (!signals.isEmpty()) {
            ruleMiningSignalRepository.saveAll(signals);
        }

        Map<RuleMiningSignalType, Long> countsByType = signals.stream()
                .collect(Collectors.groupingBy(
                        RuleMiningSignal::getSignalType,
                        () -> new EnumMap<>(RuleMiningSignalType.class),
                        Collectors.counting()
                ));

        log.info(
                "[RULE-MINING] signals ingested. runId={}, total={}, counts={}",
                runId,
                signals.size(),
                countsByType
        );

        return RuleMiningSignalIngestResult.created(
                runId,
                signals.size(),
                count(countsByType, RuleMiningSignalType.METHOD_CALL)
                        + count(countsByType, RuleMiningSignalType.OBJECT_CREATION),
                count(countsByType, RuleMiningSignalType.IF_CONDITION),
                count(countsByType, RuleMiningSignalType.THROW_STATEMENT),
                count(countsByType, RuleMiningSignalType.RETURN_STATEMENT)
        );
    }

    private void deletePreviousMiningResults(String runId) {
        ruleCandidateEvidenceRepository.deleteAllByCandidate_Run_RunId(runId);
        ruleCandidateRepository.deleteAllByRun_RunId(runId);
        ruleMiningSignalRepository.deleteAllByRun_RunId(runId);
    }

    private boolean isSignalSourceEdge(Edge edge) {
        if (edge == null || edge.getEdgeType() == null) {
            return false;
        }

        return edge.getEdgeType() == EdgeType.CALLS
                || edge.getEdgeType() == EdgeType.THROWS
                || edge.getEdgeType() == EdgeType.ACCESSES_FIELD
                || edge.getEdgeType() == EdgeType.ANNOTATED_WITH;
    }

    private boolean isMethodLikeSymbol(SymbolEntity symbol) {
        return symbol != null
                && (symbol.getSymbolKind() == SymbolKind.METHOD
                || symbol.getSymbolKind() == SymbolKind.CONSTRUCTOR);
    }

    private boolean hasSourceLocation(SymbolEntity symbol) {
        return symbol != null
                && symbol.getSourceFile() != null
                && symbol.getSourceFile().getFileId() != null
                && symbol.getSourceStartLine() != null
                && symbol.getSourceEndLine() != null;
    }

    private Map<Long, List<Evidence>> loadEvidenceByEdgeId(List<Edge> edges) {
        List<Long> edgeIds = edges.stream()
                .map(Edge::getEdgeId)
                .filter(Objects::nonNull)
                .toList();

        if (edgeIds.isEmpty()) {
            return Map.of();
        }

        List<EdgeEvidence> links = edgeEvidenceRepository.findAllByEdge_EdgeIdIn(edgeIds);

        Map<Long, List<Evidence>> result = new HashMap<>();

        for (EdgeEvidence link : links) {
            if (link == null || link.getEdge() == null || link.getEvidence() == null) {
                continue;
            }

            Long edgeId = link.getEdge().getEdgeId();
            if (edgeId == null) {
                continue;
            }

            result.computeIfAbsent(edgeId, ignored -> new ArrayList<>())
                    .add(link.getEvidence());
        }

        return result;
    }

    private void ingestEdgeSignals(
            RepoRun run,
            List<Edge> edges,
            Map<Long, List<Evidence>> evidenceByEdgeId,
            List<RuleMiningSignal> signals,
            Set<String> signalKeys
    ) {
        for (Edge edge : edges) {
            if (edge == null || edge.getEdgeType() == null) {
                continue;
            }

            List<Evidence> linkedEvidences = evidenceByEdgeId.getOrDefault(edge.getEdgeId(), List.of());
            if (linkedEvidences.isEmpty()) {
                ingestSingleEdgeSignal(run, edge, null, signals, signalKeys);
                continue;
            }

            for (Evidence evidence : linkedEvidences) {
                ingestSingleEdgeSignal(run, edge, evidence, signals, signalKeys);
            }
        }
    }

    private void ingestSingleEdgeSignal(
            RepoRun run,
            Edge edge,
            Evidence evidence,
            List<RuleMiningSignal> signals,
            Set<String> signalKeys
    ) {
        EdgeType edgeType = edge.getEdgeType();

        if (edgeType == EdgeType.CALLS) {
            String targetText = edgeTargetText(edge);

            addSignal(
                    run,
                    isObjectCreation(targetText) ? RuleMiningSignalType.OBJECT_CREATION : RuleMiningSignalType.METHOD_CALL,
                    RuleCandidateSource.GRAPHSTORE,
                    edge.getFromSymbol(),
                    edge,
                    evidence,
                    normalizeText(targetText),
                    evidence == null ? null : evidence.getSnippet(),
                    evidence == null ? null : evidence.getStartLine(),
                    evidence == null ? null : evidence.getEndLine(),
                    GRAPHSTORE_CONFIDENCE,
                    buildEdgeMeta(edge, "graphstore.calls"),
                    signals,
                    signalKeys
            );

            RuleMiningSignalType classifiedType = classifyMethodCall(targetText);
            if (classifiedType != null) {
                addSignal(
                        run,
                        classifiedType,
                        RuleCandidateSource.GRAPHSTORE,
                        edge.getFromSymbol(),
                        edge,
                        evidence,
                        normalizeText(targetText),
                        evidence == null ? null : evidence.getSnippet(),
                        evidence == null ? null : evidence.getStartLine(),
                        evidence == null ? null : evidence.getEndLine(),
                        CLASSIFIED_CALL_CONFIDENCE,
                        buildEdgeMeta(edge, "graphstore.calls.classified"),
                        signals,
                        signalKeys
                );
            }

            return;
        }

        if (edgeType == EdgeType.THROWS) {
            addSignal(
                    run,
                    RuleMiningSignalType.THROWS_DECLARATION,
                    RuleCandidateSource.GRAPHSTORE,
                    edge.getFromSymbol(),
                    edge,
                    evidence,
                    normalizeText(edgeTargetText(edge)),
                    evidence == null ? null : evidence.getSnippet(),
                    evidence == null ? null : evidence.getStartLine(),
                    evidence == null ? null : evidence.getEndLine(),
                    GRAPHSTORE_CONFIDENCE,
                    buildEdgeMeta(edge, "graphstore.throws"),
                    signals,
                    signalKeys
            );
            return;
        }

        if (edgeType == EdgeType.ACCESSES_FIELD) {
            addSignal(
                    run,
                    RuleMiningSignalType.FIELD_ACCESS,
                    RuleCandidateSource.GRAPHSTORE,
                    edge.getFromSymbol(),
                    edge,
                    evidence,
                    normalizeText(edgeTargetText(edge)),
                    evidence == null ? null : evidence.getSnippet(),
                    evidence == null ? null : evidence.getStartLine(),
                    evidence == null ? null : evidence.getEndLine(),
                    GRAPHSTORE_CONFIDENCE,
                    buildEdgeMeta(edge, "graphstore.accesses_field"),
                    signals,
                    signalKeys
            );
            return;
        }

        if (edgeType == EdgeType.ANNOTATED_WITH) {
            String annotationText = edgeTargetText(edge);

            addSignal(
                    run,
                    RuleMiningSignalType.ANNOTATION,
                    RuleCandidateSource.GRAPHSTORE,
                    edge.getFromSymbol(),
                    edge,
                    evidence,
                    normalizeText(annotationText),
                    evidence == null ? null : evidence.getSnippet(),
                    evidence == null ? null : evidence.getStartLine(),
                    evidence == null ? null : evidence.getEndLine(),
                    GRAPHSTORE_CONFIDENCE,
                    buildEdgeMeta(edge, "graphstore.annotation"),
                    signals,
                    signalKeys
            );

            if (containsIgnoreCase(annotationText, "Transactional")) {
                addSignal(
                        run,
                        RuleMiningSignalType.TRANSACTION_BOUNDARY,
                        RuleCandidateSource.GRAPHSTORE,
                        edge.getFromSymbol(),
                        edge,
                        evidence,
                        normalizeText(annotationText),
                        evidence == null ? null : evidence.getSnippet(),
                        evidence == null ? null : evidence.getStartLine(),
                        evidence == null ? null : evidence.getEndLine(),
                        CLASSIFIED_CALL_CONFIDENCE,
                        buildEdgeMeta(edge, "graphstore.annotation.transactional"),
                        signals,
                        signalKeys
                );
            }
        }
    }

    private void ingestSnippetSignals(
            RepoRun run,
            List<Evidence> evidences,
            List<SymbolEntity> methodSymbols,
            List<RuleMiningSignal> signals,
            Set<String> signalKeys
    ) {
        Map<Long, List<SymbolEntity>> methodSymbolsByFileId = buildMethodSymbolsByFileId(methodSymbols);

        for (Evidence evidence : evidences) {
            if (evidence == null || evidence.getSnippet() == null || evidence.getSnippet().isBlank()) {
                continue;
            }

            String snippet = evidence.getSnippet();

            boolean hasIf = IF_PATTERN.matcher(snippet).find();
            boolean hasThrow = THROW_PATTERN.matcher(snippet).find();
            boolean hasReturn = RETURN_PATTERN.matcher(snippet).find();
            boolean hasErrorResponse = ERROR_RESPONSE_PATTERN.matcher(snippet).find();

            if (!hasIf && !hasThrow && !hasReturn && !hasErrorResponse) {
                continue;
            }

            SymbolEntity ownerMethod = resolveOwnerMethodSymbol(evidence, methodSymbolsByFileId);

            if (hasIf) {
                addSnippetSignal(
                        run,
                        RuleMiningSignalType.IF_CONDITION,
                        ownerMethod,
                        evidence,
                        extractFirstMatchedLine(snippet, "if"),
                        signals,
                        signalKeys
                );
            }

            if (hasThrow) {
                addSnippetSignal(
                        run,
                        RuleMiningSignalType.THROW_STATEMENT,
                        ownerMethod,
                        evidence,
                        extractFirstMatchedLine(snippet, "throw"),
                        signals,
                        signalKeys
                );
            }

            if (hasReturn) {
                addSnippetSignal(
                        run,
                        RuleMiningSignalType.RETURN_STATEMENT,
                        ownerMethod,
                        evidence,
                        extractFirstMatchedLine(snippet, "return"),
                        signals,
                        signalKeys
                );
            }

            if (hasErrorResponse) {
                addSnippetSignal(
                        run,
                        RuleMiningSignalType.ERROR_RESPONSE,
                        ownerMethod,
                        evidence,
                        extractErrorResponseLine(snippet),
                        signals,
                        signalKeys
                );
            }
        }
    }

    private Map<Long, List<SymbolEntity>> buildMethodSymbolsByFileId(List<SymbolEntity> methodSymbols) {
        Map<Long, List<SymbolEntity>> result = new HashMap<>();

        if (methodSymbols == null || methodSymbols.isEmpty()) {
            return result;
        }

        for (SymbolEntity symbol : methodSymbols) {
            if (!hasSourceLocation(symbol)) {
                continue;
            }

            Long fileId = symbol.getSourceFile().getFileId();
            result.computeIfAbsent(fileId, ignored -> new ArrayList<>()).add(symbol);
        }

        for (List<SymbolEntity> fileSymbols : result.values()) {
            fileSymbols.sort(
                    Comparator.comparing(
                                    SymbolEntity::getSourceStartLine,
                                    Comparator.nullsLast(Integer::compareTo)
                            )
                            .thenComparingInt(this::symbolSpanSize)
                            .thenComparing(SymbolEntity::getSymbolId)
            );
        }

        return result;
    }

    private SymbolEntity resolveOwnerMethodSymbol(
            Evidence evidence,
            Map<Long, List<SymbolEntity>> methodSymbolsByFileId
    ) {
        if (evidence == null || evidence.getFile() == null || evidence.getFile().getFileId() == null) {
            return null;
        }

        if (evidence.getStartLine() == null) {
            return null;
        }

        Long fileId = evidence.getFile().getFileId();
        Integer evidenceLine = evidence.getStartLine();

        List<SymbolEntity> candidates = methodSymbolsByFileId.getOrDefault(fileId, List.of());
        if (candidates.isEmpty()) {
            return null;
        }

        SymbolEntity best = null;
        int bestSpan = Integer.MAX_VALUE;

        for (SymbolEntity candidate : candidates) {
            if (!containsLine(candidate, evidenceLine)) {
                continue;
            }

            int span = symbolSpanSize(candidate);
            if (span < bestSpan) {
                best = candidate;
                bestSpan = span;
            }
        }

        return best;
    }

    private boolean containsLine(SymbolEntity symbol, Integer line) {
        if (symbol == null || line == null) {
            return false;
        }

        if (symbol.getSourceStartLine() == null || symbol.getSourceEndLine() == null) {
            return false;
        }

        return symbol.getSourceStartLine() <= line && line <= symbol.getSourceEndLine();
    }

    private int symbolSpanSize(SymbolEntity symbol) {
        if (symbol == null || symbol.getSourceStartLine() == null || symbol.getSourceEndLine() == null) {
            return Integer.MAX_VALUE;
        }

        return Math.max(0, symbol.getSourceEndLine() - symbol.getSourceStartLine());
    }

    private void addSnippetSignal(
            RepoRun run,
            RuleMiningSignalType type,
            SymbolEntity ownerMethod,
            Evidence evidence,
            String normalizedText,
            List<RuleMiningSignal> signals,
            Set<String> signalKeys
    ) {
        addSignal(
                run,
                type,
                RuleCandidateSource.EVIDENCE_SNIPPET,
                ownerMethod,
                null,
                evidence,
                normalizeText(normalizedText),
                evidence.getSnippet(),
                evidence.getStartLine(),
                evidence.getEndLine(),
                SNIPPET_CONFIDENCE,
                buildSnippetMeta(type),
                signals,
                signalKeys
        );
    }

    private void addSignal(
            RepoRun run,
            RuleMiningSignalType signalType,
            RuleCandidateSource source,
            SymbolEntity symbol,
            Edge edge,
            Evidence evidence,
            String normalizedText,
            String snippet,
            Integer startLine,
            Integer endLine,
            BigDecimal confidenceHint,
            JsonNode meta,
            List<RuleMiningSignal> signals,
            Set<String> signalKeys
    ) {
        String key = buildSignalKey(signalType, symbol, edge, evidence, normalizedText);
        if (!signalKeys.add(key)) {
            return;
        }

        signals.add(RuleMiningSignal.of(
                run,
                signalType,
                source,
                symbol,
                edge,
                evidence,
                normalizedText,
                snippet,
                startLine,
                endLine,
                confidenceHint,
                meta
        ));
    }

    private String buildSignalKey(
            RuleMiningSignalType signalType,
            SymbolEntity symbol,
            Edge edge,
            Evidence evidence,
            String normalizedText
    ) {
        return signalType + "|"
                + (symbol == null ? "-" : symbol.getSymbolId()) + "|"
                + (edge == null ? "-" : edge.getEdgeId()) + "|"
                + (evidence == null ? "-" : evidence.getEvidenceId()) + "|"
                + normalizeText(normalizedText);
    }

    private RuleMiningSignalType classifyMethodCall(String targetText) {
        String text = normalizeText(targetText);
        if (text == null) {
            return null;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        String name = simpleCallName(lower);

        if (isRepositorySaveCall(name)) {
            return RuleMiningSignalType.REPOSITORY_SAVE;
        }

        if (isRepositoryUpdateCall(name)) {
            return RuleMiningSignalType.REPOSITORY_UPDATE;
        }

        if (isRepositoryDeleteCall(name)) {
            return RuleMiningSignalType.REPOSITORY_DELETE;
        }

        if (isRequireCall(lower, name)) {
            return RuleMiningSignalType.REQUIRE_CALL;
        }

        if (isAssertionCall(lower, name)) {
            return RuleMiningSignalType.ASSERTION_CALL;
        }

        return null;
    }

    private boolean isRepositorySaveCall(String name) {
        return Set.of("save", "saveall", "persist", "insert").contains(name);
    }

    private boolean isRepositoryUpdateCall(String name) {
        return Set.of("update", "merge", "flush", "saveandflush").contains(name);
    }

    private boolean isRepositoryDeleteCall(String name) {
        return name.startsWith("delete") || Set.of("remove", "deleteall").contains(name);
    }

    private boolean isRequireCall(String text, String name) {
        return text.contains("requirenonnull")
                || name.startsWith("require")
                || name.equals("notnull")
                || name.equals("hastext");
    }

    private boolean isAssertionCall(String text, String name) {
        return text.contains("checkargument")
                || text.contains("checkstate")
                || name.startsWith("assert")
                || name.equals("istrue")
                || name.equals("state");
    }

    private boolean isObjectCreation(String targetText) {
        String text = normalizeText(targetText);
        return text != null && text.toLowerCase(Locale.ROOT).startsWith("new ");
    }

    private String edgeTargetText(Edge edge) {
        if (edge == null) {
            return null;
        }

        if (edge.getToSymbol() != null) {
            if (edge.getToSymbol().getQualifiedName() != null) {
                return edge.getToSymbol().getQualifiedName();
            }
            return edge.getToSymbol().getSymbolId();
        }

        JsonNode rawRef = edge.getToRawRef();
        if (rawRef == null || rawRef.isNull()) {
            return null;
        }

        JsonNode raw = rawRef.get("raw");
        if (raw != null && !raw.isNull()) {
            return raw.asText();
        }

        return rawRef.toString();
    }

    private ObjectNode buildEdgeMeta(Edge edge, String sourceDetail) {
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("sourceDetail", sourceDetail);

        if (edge == null) {
            return meta;
        }

        if (edge.getEdgeId() != null) {
            meta.put("edgeId", edge.getEdgeId());
        }
        meta.put("edgeType", edge.getEdgeType() == null ? null : edge.getEdgeType().name());
        meta.put("fromSymbolId", edge.getFromSymbol() == null ? null : edge.getFromSymbol().getSymbolId());
        meta.put("toSymbolId", edge.getToSymbol() == null ? null : edge.getToSymbol().getSymbolId());

        if (edge.getToRawRef() != null && !edge.getToRawRef().isNull()) {
            meta.set("toRawRef", edge.getToRawRef());
        }

        if (edge.getResolution() != null) {
            meta.put("resolution", edge.getResolution().name());
        }

        return meta;
    }

    private ObjectNode buildSnippetMeta(RuleMiningSignalType type) {
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("sourceDetail", "evidence.snippet.heuristic");
        meta.put("heuristicType", type.name());
        return meta;
    }

    private String extractFirstMatchedLine(String snippet, String keyword) {
        if (snippet == null) {
            return null;
        }

        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        String[] lines = snippet.split("\\R");

        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                return line.trim();
            }
        }

        return snippet.trim();
    }

    private String extractErrorResponseLine(String snippet) {
        if (snippet == null) {
            return null;
        }

        String[] lines = snippet.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("errorresponse")
                    || lower.contains("responseentity.status")
                    || lower.contains("responseentity.badrequest")
                    || lower.contains("responseentity.notfound")
                    || lower.contains(".error(")) {
                return line.trim();
            }
        }

        return snippet.trim();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value
                .replaceAll("\\s+", " ")
                .trim();

        return normalized.isBlank() ? null : normalized;
    }

    private String simpleCallName(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String value = text.trim();

        if (value.startsWith("new ")) {
            value = value.substring("new ".length()).trim();
        }

        int parenIndex = value.indexOf('(');
        if (parenIndex >= 0) {
            value = value.substring(0, parenIndex);
        }

        int hashIndex = value.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex < value.length() - 1) {
            value = value.substring(hashIndex + 1);
        }

        int dotIndex = value.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < value.length() - 1) {
            value = value.substring(dotIndex + 1);
        }

        return value.replaceAll("[^a-zA-Z0-9_]", "").toLowerCase(Locale.ROOT);
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        if (text == null || keyword == null) {
            return false;
        }

        return text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private int count(Map<RuleMiningSignalType, Long> countsByType, RuleMiningSignalType type) {
        return Math.toIntExact(countsByType.getOrDefault(type, 0L));
    }

    public record RuleMiningSignalIngestResult(
            String runId,
            boolean skipped,
            int totalSignals,
            int graphCallSignals,
            int ifConditionSignals,
            int throwStatementSignals,
            int returnStatementSignals
    ) {
        public static RuleMiningSignalIngestResult skipped(String runId, int totalSignals) {
            return new RuleMiningSignalIngestResult(
                    runId,
                    true,
                    totalSignals,
                    0,
                    0,
                    0,
                    0
            );
        }

        public static RuleMiningSignalIngestResult created(
                String runId,
                int totalSignals,
                int graphCallSignals,
                int ifConditionSignals,
                int throwStatementSignals,
                int returnStatementSignals
        ) {
            return new RuleMiningSignalIngestResult(
                    runId,
                    false,
                    totalSignals,
                    graphCallSignals,
                    ifConditionSignals,
                    throwStatementSignals,
                    returnStatementSignals
            );
        }
    }
}