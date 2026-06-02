package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateDisplayPolicyJson;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateEvidenceJson;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateItem;
import com.example.ossdoc.domain.rule.dto.json.RuleMethodCardJson;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateSummaryJson;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidatesJson;
import com.example.ossdoc.domain.rule.dto.json.SymbolSourceIndexItemJson;
import com.example.ossdoc.domain.rule.dto.json.SymbolSourceIndexJson;
import com.example.ossdoc.domain.rule.entity.RuleCandidate;
import com.example.ossdoc.domain.rule.entity.RuleCandidateEvidence;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import com.example.ossdoc.domain.rule.repository.RuleCandidateEvidenceRepository;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.rule.repository.RuleMiningSignalRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCandidateArtifactPublisher {

    private static final String SCHEMA_VERSION = "1.3";
    private static final String RELATIVE_PATH = "rule/rule_candidates.json";
    private static final String SOURCE_INDEX_SCHEMA_VERSION = "1.0";
    private static final String SOURCE_INDEX_RELATIVE_PATH = "analysis/symbol_source_index.json";
    private static final BigDecimal LOW_SCORE_THRESHOLD = new BigDecimal("0.6000");
    private static final int MAX_METHOD_CARDS = 80;

    private static final Set<String> PRIMARY_EVIDENCE_ROLES = Set.of(
            "IF_CONDITION",
            "THROW_STATEMENT",
            "RETURN_STATEMENT",
            "ERROR_RESPONSE",
            "REPOSITORY_SAVE",
            "REPOSITORY_UPDATE",
            "REPOSITORY_DELETE",
            "ASSERTION_CALL",
            "REQUIRE_CALL"
    );

    private final RuleCandidateRepository ruleCandidateRepository;
    private final RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository;
    private final RuleMiningSignalRepository ruleMiningSignalRepository;
    private final SymbolRepository symbolRepository;
    private final SymbolEvidenceRepository symbolEvidenceRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactService artifactService;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    /**
     * run 기준으로 rule_candidates 아티팩트를 최종 조립/저장한다.
     * - 후보(candidates) + 표시 정책(displayPolicy) + 메서드 카드(methodCards)를 한 번에 만든다.
     */
    @Transactional
    public Artifact publish(RepoRun run) {
        List<RuleCandidate> candidates =
                ruleCandidateRepository.findAllWithSubjectByRunIdOrderByScoreDesc(run.getRunId());

        List<Long> candidateIds = candidates.stream()
                .map(RuleCandidate::getCandidateId)
                .toList();

        Map<Long, List<RuleCandidateEvidence>> evidencesByCandidateId =
                loadEvidencesByCandidateId(candidateIds);

        RuleCandidateSummaryJson summary = buildSummary(candidates);

        Set<String> publicApiOwnerFqns = resolvePublicApiOwnerFqns(run.getRunId());

        List<RuleCandidateItem> items = new ArrayList<>();
        for (RuleCandidate candidate : candidates) {
            List<RuleCandidateEvidence> evidences =
                    evidencesByCandidateId.getOrDefault(candidate.getCandidateId(), List.of());

            CandidateQuality quality = evaluateQuality(candidate, evidences);
            items.add(toItem(candidate, evidences, quality, publicApiOwnerFqns));
        }

        List<RuleMethodCardJson> methodCards = buildMethodCards(run.getRunId(), candidates);

        long edgeCount = edgeRepository.countByRun_RunId(run.getRunId());
        RuleCandidateDisplayPolicyJson displayPolicy = buildDisplayPolicy(candidateIds, edgeCount);

        RuleCandidatesJson output = RuleCandidatesJson.builder()
                .schemaVersion(SCHEMA_VERSION)
                .runId(run.getRunId())
                .generatedAt(OffsetDateTime.now().toString())
                .summary(summary)
                .displayPolicy(displayPolicy)
                .methodCards(methodCards)
                .candidates(items)
                .build();

        JsonNode content = objectMapper.valueToTree(output);

        Artifact artifact = artifactService.saveJsonArtifact(
                run,
                ArtifactKind.RULE_CANDIDATES_JSON,
                SCHEMA_VERSION,
                RELATIVE_PATH,
                content
        );

        /*
         * 우선순위 4: 심볼-소스 브릿지 인덱스를 함께 발행한다.
         * - 실패해도 RULE 산출물 자체는 유지해 파이프라인 안정성을 지킨다.
         */
        try {
            publishSymbolSourceIndex(run);
        } catch (Exception e) {
            log.warn(
                    "[RULE-MINING] symbol_source_index publish failed. runId={}",
                    run.getRunId(),
                    e
            );
        }

        log.info(
                "[RULE-MINING] rule_candidates.json published. runId={}, artifactId={}, candidates={}, methodCards={}, primary={}",
                run.getRunId(),
                artifact.getArtifactId(),
                candidates.size(),
                methodCards.size(),
                displayPolicy.recommendedPrimaryCount()
        );

        return artifact;
    }

    /**
     * 우선순위 4 브릿지 산출물(symbol_source_index.json)을 생성/저장한다.
     *
     * - symbol_id 기준으로 fqn/소스 위치/근거 ID/evidence confidence를 한 번에 조회 가능하게 만든다.
     * - LLM 입력 조립에서 api_map/rankings 누락 앵커를 보강하는 기반 데이터로 사용된다.
     */
    private void publishSymbolSourceIndex(RepoRun run) {
        String runId = run.getRunId();
        List<SymbolEntity> symbols = symbolRepository.findAllWithOwnerAndSourceByRunId(runId);
        if (symbols == null || symbols.isEmpty()) {
            return;
        }

        // owner-type 소스 승격에 사용할 symbolId → .java 경로 맵 (이미 fetch된 sourceFile만 참조)
        Map<String, String> javaPathBySymbolId = buildJavaPathBySymbolId(symbols);

        Map<String, SymbolCoverageAggregate> aggregateBySymbolId = new HashMap<>();
        for (SymbolEntity symbol : symbols) {
            if (symbol == null || symbol.getSymbolId() == null || symbol.getSymbolId().isBlank()) {
                continue;
            }
            aggregateBySymbolId.put(symbol.getSymbolId(), new SymbolCoverageAggregate(symbol));
        }
        if (aggregateBySymbolId.isEmpty()) {
            return;
        }

        // 1) symbol_evidence 기준으로 evidence id와 라인 범위를 누적한다.
        List<com.example.ossdoc.domain.graphstore.entity.SymbolEvidence> symbolEvidences =
                symbolEvidenceRepository.findAllWithEvidenceByRunId(runId);
        for (com.example.ossdoc.domain.graphstore.entity.SymbolEvidence symbolEvidence : symbolEvidences) {
            if (symbolEvidence == null || symbolEvidence.getSymbol() == null) {
                continue;
            }
            SymbolCoverageAggregate aggregate =
                    aggregateBySymbolId.get(symbolEvidence.getSymbol().getSymbolId());
            if (aggregate == null || symbolEvidence.getEvidence() == null) {
                continue;
            }
            aggregate.addEvidenceId(symbolEvidence.getEvidence().getEvidenceId());
            aggregate.addLineRange(
                    symbolEvidence.getEvidence().getStartLine(),
                    symbolEvidence.getEvidence().getEndLine()
            );
        }

        // 2) signal 기준으로 confidence 힌트와 보조 라인 범위를 누적한다.
        List<RuleMiningSignal> signals = ruleMiningSignalRepository.findAllWithSymbolAndSourceByRunId(runId);
        for (RuleMiningSignal signal : signals) {
            if (signal == null || signal.getSymbol() == null) {
                continue;
            }
            SymbolCoverageAggregate aggregate = aggregateBySymbolId.get(signal.getSymbol().getSymbolId());
            if (aggregate == null) {
                continue;
            }
            aggregate.addSignalType(signal.getSignalType());
            aggregate.addSignalConfidence(signal.getConfidenceHint());
            aggregate.addLineRange(signal.getStartLine(), signal.getEndLine());

            if (signal.getEvidence() != null) {
                aggregate.addEvidenceId(signal.getEvidence().getEvidenceId());
                aggregate.addLineRange(
                        signal.getEvidence().getStartLine(),
                        signal.getEvidence().getEndLine()
                );
            }
        }

        List<SymbolSourceIndexItemJson> items = new ArrayList<>();
        for (SymbolCoverageAggregate aggregate : aggregateBySymbolId.values()) {
            SymbolEntity symbol = aggregate.symbol();
            String fqn = safeText(symbol.getQualifiedName());
            if (fqn.isBlank()) {
                continue;
            }

            String rawSourceFile = symbol.getSourceFile() == null ? "" : safeText(symbol.getSourceFile().getPath());

            // ① synthetic 필터: <clinit>, enum values/valueOf/.VALUES는 소스 선언이 없으므로 제외
            if (isSyntheticCompilerSymbol(fqn, rawSourceFile)) {
                continue;
            }

            // ② owner-type 소스 승격: .class 백킹 심볼의 파일을 owner의 .java 경로로 교체
            String sourceFile = rawSourceFile;
            Integer startLine = firstNonNull(symbol.getSourceStartLine(), aggregate.minStartLine());
            Integer endLine = firstNonNull(symbol.getSourceEndLine(), aggregate.maxEndLine());
            if (isClassFile(sourceFile) && symbol.getOwner() != null) {
                String ownerJavaPath = javaPathBySymbolId.get(symbol.getOwner().getSymbolId());
                if (ownerJavaPath != null) {
                    sourceFile = ownerJavaPath;
                    // 바이트코드 라인 번호는 .java 위치와 다르므로 제거
                    startLine = null;
                    endLine = null;
                }
            }

            items.add(SymbolSourceIndexItemJson.builder()
                    .symbolId(symbol.getSymbolId())
                    .fqn(fqn)
                    .symbolKind(symbol.getSymbolKind() == null ? "" : symbol.getSymbolKind().name())
                    .ownerTypeFqn(resolveOwnerTypeFqn(symbol))
                    .sourceFile(sourceFile)
                    .startLine(startLine)
                    .endLine(endLine)
                    .evidenceIds(aggregate.sortedEvidenceIds())
                    .confidence(aggregate.toConfidenceScore(sourceFile, startLine, endLine))
                    .build());
        }

        items.sort(Comparator
                .comparing(SymbolSourceIndexItemJson::fqn, Comparator.nullsLast(String::compareTo))
                .thenComparing(SymbolSourceIndexItemJson::symbolId, Comparator.nullsLast(String::compareTo)));

        SymbolSourceIndexJson output = SymbolSourceIndexJson.builder()
                .schemaVersion(SOURCE_INDEX_SCHEMA_VERSION)
                .runId(runId)
                .generatedAt(OffsetDateTime.now().toString())
                .symbolCount(items.size())
                .symbols(List.copyOf(items))
                .build();

        artifactService.saveJsonArtifact(
                run,
                ArtifactKind.SYMBOL_SOURCE_INDEX_JSON,
                SOURCE_INDEX_SCHEMA_VERSION,
                SOURCE_INDEX_RELATIVE_PATH,
                objectMapper.valueToTree(output)
        );
    }

    /**
     * 이미 fetch된 symbols 목록에서 symbolId → .java 소스 경로 맵을 만든다.
     * owner-type 소스 승격 시 추가 쿼리 없이 파일 경로를 조회하기 위해 사용한다.
     */
    private Map<String, String> buildJavaPathBySymbolId(List<SymbolEntity> symbols) {
        Map<String, String> result = new HashMap<>();
        for (SymbolEntity symbol : symbols) {
            if (symbol.getSymbolId() == null || symbol.getSourceFile() == null) continue;
            String path = symbol.getSourceFile().getPath();
            if (isJavaSourcePath(path)) {
                result.put(symbol.getSymbolId(), path);
            }
        }
        return result;
    }

    /**
     * Java 컴파일러가 바이트코드에만 자동 생성하는 심볼인지 판별한다.
     * - &lt;clinit&gt;: static 초기화 메서드 — 모든 클래스에서 소스 선언 없음
     * - values/valueOf/.VALUES: enum 자동 생성 — .class 출처인 경우만 필터
     * - 익명 클래스($1, $2...): 소스에 이름 있는 선언 없는 inline 클래스 — .class 출처인 경우만 필터
     * - package-info: 패키지 메타 선언이며 LLM 심볼 조회에 불필요 — .class 출처인 경우만 필터
     */
    private boolean isSyntheticCompilerSymbol(String fqn, String sourceFile) {
        if (fqn.contains("#<clinit>()")) return true;
        if (isClassFile(sourceFile)) {
            if (fqn.contains("#values()")) return true;
            if (fqn.contains("#valueOf(java.lang.String)")) return true;
            if (fqn.contains("#.VALUES")) return true;
            if (containsAnonymousClassSegment(fqn)) return true;
            if (fqn.contains("package-info")) return true;
        }
        return false;
    }

    /**
     * fqn 타입 부분(kind 접두어와 # 사이)에 익명 클래스 패턴($숫자)이 있는지 확인한다.
     * 예) type:Outer$1, method:Outer$1#foo(), field:Outer$1#val$x
     */
    private boolean containsAnonymousClassSegment(String fqn) {
        int colon = fqn.indexOf(':');
        int hash = fqn.indexOf('#');
        String typePart = colon >= 0
                ? (hash > colon ? fqn.substring(colon + 1, hash) : fqn.substring(colon + 1))
                : fqn;
        for (int i = 0; i < typePart.length() - 1; i++) {
            if (typePart.charAt(i) == '$' && Character.isDigit(typePart.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean isClassFile(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".class");
    }

    private boolean isJavaSourcePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt");
    }

    /**
     * owner 체인을 따라가며 가장 가까운 TYPE 심볼 FQN을 찾는다.
     */
    private String resolveOwnerTypeFqn(SymbolEntity symbol) {
        if (symbol == null) {
            return "";
        }
        if (symbol.getSymbolKind() == SymbolKind.TYPE) {
            return safeText(symbol.getQualifiedName());
        }

        SymbolEntity cursor = symbol.getOwner();
        while (cursor != null) {
            if (cursor.getSymbolKind() == SymbolKind.TYPE) {
                return safeText(cursor.getQualifiedName());
            }
            cursor = cursor.getOwner();
        }
        return "";
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 후보 ID 목록 기준으로 evidence 링크를 미리 로드하고 candidateId별로 묶는다.
     */
    private Map<Long, List<RuleCandidateEvidence>> loadEvidencesByCandidateId(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Map.of();
        }

        return ruleCandidateEvidenceRepository.findAllWithRelationsByCandidateIdIn(candidateIds)
                .stream()
                .collect(Collectors.groupingBy(evidence -> evidence.getCandidate().getCandidateId()));
    }

    /**
     * 후보 전체 통계(신뢰도별 개수)를 계산해 summary 블록을 만든다.
     */
    private RuleCandidateSummaryJson buildSummary(List<RuleCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return RuleCandidateSummaryJson.builder()
                    .totalCandidates(0)
                    .highConfidenceCount(0)
                    .mediumConfidenceCount(0)
                    .lowConfidenceCount(0)
                    .build();
        }

        int high = 0;
        int medium = 0;
        int low = 0;

        for (RuleCandidate candidate : candidates) {
            if (candidate.getConfidence() == RuleCandidateConfidence.HIGH) {
                high++;
            } else if (candidate.getConfidence() == RuleCandidateConfidence.MEDIUM) {
                medium++;
            } else if (candidate.getConfidence() == RuleCandidateConfidence.LOW) {
                low++;
            }
        }

        return RuleCandidateSummaryJson.builder()
                .totalCandidates(candidates.size())
                .highConfidenceCount(high)
                .mediumConfidenceCount(medium)
                .lowConfidenceCount(low)
                .build();
    }

    /**
     * RuleCandidate 엔티티 + evidence 목록을 API/JSON 응답용 아이템으로 변환한다.
     * publicApiOwnerFqns: API_SURFACE_JSON에서 추출한 공개 API 타입 FQN 집합 — 조인해 publicApiRelated를 산정한다.
     */
    private RuleCandidateItem toItem(
            RuleCandidate candidate,
            List<RuleCandidateEvidence> evidences,
            CandidateQuality quality,
            Set<String> publicApiOwnerFqns
    ) {
        SymbolEntity subject = candidate.getSubjectSymbol();
        List<RuleCandidateEvidenceJson> evidenceJson = toEvidenceJsonList(evidences);

        boolean publicApiRelated = false;
        if (subject != null && subject.getQualifiedName() != null) {
            String ownerFqn = extractOwnerFqn(subject.getQualifiedName());
            publicApiRelated = !ownerFqn.isBlank() && publicApiOwnerFqns.contains(ownerFqn);
        }

        return RuleCandidateItem.builder()
                .candidateId(candidate.getCandidateId())
                .ruleKey(candidate.getRuleKey())
                .candidateKind(candidate.getCandidateKind() == null ? null : candidate.getCandidateKind().name())
                .confidence(candidate.getConfidence() == null ? null : candidate.getConfidence().name())
                .status(candidate.getStatus() == null ? null : candidate.getStatus().name())
                .source(candidate.getSource() == null ? null : candidate.getSource().name())
                .subjectSymbolId(subject == null ? null : subject.getSymbolId())
                .subjectQualifiedName(subject == null ? null : subject.getQualifiedName())
                .groupId(candidate.getGroupId())
                .title(candidate.getTitle())
                .description(candidate.getDescription())
                .fingerprint(candidate.getFingerprint())
                .score(candidate.getScore())
                .supportCount(candidate.getSupportCount())
                .publicApiRelated(publicApiRelated)
                .summary(candidate.getSummary())
                .impact(candidate.getImpact())
                .meta(candidate.getMeta())
                .estimated(quality.estimated())
                .qualityLabel(quality.estimated() ? "ESTIMATED" : "CONFIRMED")
                .qualityReason(quality.reason())
                .evidenceCount(evidenceJson.size())
                .evidences(evidenceJson)
                .build();
    }

    /**
     * API_SURFACE_JSON + API_MAP_JSON 에서 공개 API 타입 FQN 집합을 추출한다.
     * entry_points + extension_points 양쪽을 수집해 규칙 대상 소유 타입과 조인에 사용한다.
     */
    private Set<String> resolvePublicApiOwnerFqns(String runId) {
        Set<String> fqns = new HashSet<>();
        try {
            artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.API_SURFACE_JSON)
                    .map(Artifact::getMeta)
                    .ifPresent(meta -> {
                        collectFqnsFromArray(fqns, meta.path("entry_points"));
                        collectFqnsFromArray(fqns, meta.path("extension_points"));
                    });
            artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.API_MAP_JSON)
                    .map(Artifact::getMeta)
                    .ifPresent(meta -> {
                        collectFqnsFromArray(fqns, meta.path("entry_points"));
                        collectFqnsFromArray(fqns, meta.path("entryPoints"));
                        collectFqnsFromArray(fqns, meta.path("extension_points"));
                        collectFqnsFromArray(fqns, meta.path("extensionPoints"));
                        collectFqnsFromArray(fqns, meta.path("coreClasses"));
                    });
        } catch (Exception e) {
            log.warn("[RULE-MINING] publicApiOwnerFqns resolve failed. runId={}", runId, e);
        }
        return fqns;
    }

    private void collectFqnsFromArray(Set<String> fqns, com.fasterxml.jackson.databind.JsonNode array) {
        if (array == null || !array.isArray()) return;
        for (com.fasterxml.jackson.databind.JsonNode item : array) {
            String fqn = firstNonBlank(
                    item.path("fqn").asText(""),
                    item.path("qualifiedName").asText(""),
                    item.path("qualified_name").asText(""),
                    item.path("classFqn").asText(""),
                    item.path("class_fqn").asText("")
            );
            String symbolId = firstNonBlank(item.path("symbolId").asText(""), item.path("symbol_id").asText(""));
            if (fqn.isBlank() && symbolId.startsWith("type:")) {
                fqn = symbolId.substring("type:".length());
            }
            if (!fqn.isBlank()) {
                fqns.add(fqn);
            }
        }
    }

    /**
     * 후보를 ESTIMATED/CONFIRMED로 분류한다.
     * - confidence, evidence 밀도, score/support를 함께 평가한다.
     */
    private CandidateQuality evaluateQuality(RuleCandidate candidate, List<RuleCandidateEvidence> evidences) {
        int evidenceCount = evidences == null ? 0 : evidences.size();
        int primaryEvidenceCount = countPrimaryEvidence(evidences);

        boolean lowConfidence = candidate.getConfidence() == RuleCandidateConfidence.LOW;
        boolean sparseEvidence = evidenceCount == 0 || (evidenceCount == 1 && primaryEvidenceCount == 0);
        boolean lowScore = candidate.getScore() != null
                && candidate.getScore().compareTo(LOW_SCORE_THRESHOLD) < 0;
        boolean weakSupport = candidate.getSupportCount() == null || candidate.getSupportCount() <= 1;

        if (lowConfidence) {
            return new CandidateQuality(true, "LOW confidence");
        }
        if (sparseEvidence) {
            return new CandidateQuality(true, "insufficient evidence links");
        }
        if (lowScore && weakSupport) {
            return new CandidateQuality(true, "low score and weak support");
        }
        return new CandidateQuality(false, "sufficient evidence");
    }

    /**
     * evidence 중 핵심 역할(primary role) 개수를 계산한다.
     */
    private int countPrimaryEvidence(List<RuleCandidateEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return 0;
        }

        return (int) evidences.stream()
                .map(RuleCandidateEvidence::getRole)
                .filter(role -> role != null && PRIMARY_EVIDENCE_ROLES.contains(role))
                .count();
    }

    /**
     * 화면 기본 노출 개수를 정하기 위한 displayPolicy를 계산한다.
     */
    private RuleCandidateDisplayPolicyJson buildDisplayPolicy(List<Long> orderedCandidateIds, long edgeCount) {
        int total = orderedCandidateIds.size();
        DisplayTier tier = resolveDisplayTier(total, edgeCount);

        List<Long> primaryIds = new ArrayList<>();
        for (Long candidateId : orderedCandidateIds) {
            if (primaryIds.size() >= tier.targetMax()) {
                break;
            }
            primaryIds.add(candidateId);
        }

        if (primaryIds.isEmpty() && !orderedCandidateIds.isEmpty()) {
            primaryIds.add(orderedCandidateIds.get(0));
        }

        Set<Long> primaryIdSet = new HashSet<>(primaryIds);
        List<Long> exploratoryIds = orderedCandidateIds.stream()
                .filter(id -> !primaryIdSet.contains(id))
                .toList();

        return RuleCandidateDisplayPolicyJson.builder()
                .sizeTier(tier.name())
                .totalCandidates(total)
                .targetMin(tier.targetMin())
                .targetMax(tier.targetMax())
                .recommendedPrimaryCount(primaryIds.size())
                .primaryCandidateIds(primaryIds)
                .exploratoryCandidateIds(exploratoryIds)
                .build();
    }

    /**
     * 후보 수/그래프 규모에 따라 SMALL~XLARGE 표시 티어를 결정한다.
     */
    private DisplayTier resolveDisplayTier(int totalCandidates, long edgeCount) {
        DisplayTier baseTier;
        if (totalCandidates <= 40) {
            baseTier = DisplayTier.SMALL;
        } else if (totalCandidates <= 120) {
            baseTier = DisplayTier.MEDIUM;
        } else if (totalCandidates <= 260) {
            baseTier = DisplayTier.LARGE;
        } else {
            baseTier = DisplayTier.XLARGE;
        }

        int boost = edgeCount >= 30_000 ? 2 : edgeCount >= 12_000 ? 1 : 0;
        return DisplayTier.byIndex(baseTier.ordinal() + boost);
    }

    /**
     * evidence 엔티티 리스트를 JSON DTO 리스트로 변환한다.
     */
    private List<RuleCandidateEvidenceJson> toEvidenceJsonList(List<RuleCandidateEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }

        return evidences.stream()
                .map(this::toEvidenceJson)
                .toList();
    }

    /**
     * 단일 evidence 엔티티를 JSON DTO로 변환한다.
     * BYTECODE 타입 evidence는 snippet을 제외한다 — 역어셈블 텍스트는 사용자에게 무의미하다.
     */
    private RuleCandidateEvidenceJson toEvidenceJson(RuleCandidateEvidence evidence) {
        Evidence ev = evidence.getEvidence();
        EvidenceType evType = (ev != null) ? ev.getEvidenceType() : null;
        boolean isBytecode = evType == EvidenceType.BYTECODE;

        return RuleCandidateEvidenceJson.builder()
                .candidateEvidenceId(evidence.getCandidateEvidenceId())
                .signalId(evidence.getSignal() == null ? null : evidence.getSignal().getSignalId())
                .evidenceId(ev == null ? null : ev.getEvidenceId())
                .rawId(ev == null ? null : ev.getRawId())
                .evidenceType(evType != null ? evType.name() : null)
                .edgeId(evidence.getEdge() == null ? null : evidence.getEdge().getEdgeId())
                .role(evidence.getRole())
                .weight(evidence.getWeight())
                .filePath(evidence.getFilePath())
                .startLine(evidence.getStartLine())
                .endLine(evidence.getEndLine())
                .snippet(isBytecode ? null : evidence.getSnippet())
                .note(evidence.getNote())
                .build();
    }

    /**
     * 우선순위 3: rule_candidates를 "패턴 후보 목록"에서 "메서드 기능 카드 입력원"까지 확장한다.
     *
     * - RuleMiningSignal 전체에서 METHOD/CONSTRUCTOR 단위로 그룹핑
     * - method_fqn, source_file, line_range, summary_hint를 카드로 생성
     * - LLM이 특정 메서드를 설명할 때 규칙 후보 유무와 관계없이 최소 설명 단서를 확보하도록 한다.
     */
    private List<RuleMethodCardJson> buildMethodCards(String runId, List<RuleCandidate> candidates) {
        List<RuleMiningSignal> signals = ruleMiningSignalRepository.findAllWithSymbolAndSourceByRunId(runId);
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }

        // C-4: 영속 프레임워크(@Transactional) 신호가 없으면 REPOSITORY_* 신호는
        // 이름 패턴(save/update/delete) 오탐일 가능성이 높다. PersistenceActionMiner
        // 가드와 동일하게, persistence 행위 분류·설명을 산출하지 않도록 해당 신호를 제외한다.
        boolean hasPersistenceFramework = ruleMiningSignalRepository.existsByRun_RunIdAndSignalType(
                runId,
                RuleMiningSignalType.TRANSACTION_BOUNDARY
        );

        Map<String, List<RuleCandidate>> candidatesByMethodSymbolId = new HashMap<>();
        for (RuleCandidate candidate : candidates) {
            SymbolEntity subject = candidate.getSubjectSymbol();
            if (!isMethodSymbol(subject)) {
                continue;
            }
            candidatesByMethodSymbolId
                    .computeIfAbsent(subject.getSymbolId(), ignored -> new ArrayList<>())
                    .add(candidate);
        }

        Map<String, MethodSignalAggregate> aggregateByMethod = new HashMap<>();
        for (RuleMiningSignal signal : signals) {
            SymbolEntity symbol = signal.getSymbol();
            if (!isMethodSymbol(symbol)) {
                continue;
            }
            if (symbol.getSymbolId() == null || symbol.getSymbolId().isBlank()) {
                continue;
            }
            if (!hasPersistenceFramework && isPersistenceSignal(signal.getSignalType())) {
                continue;
            }
            aggregateByMethod
                    .computeIfAbsent(symbol.getSymbolId(), ignored -> new MethodSignalAggregate(symbol))
                    .add(signal);
        }

        if (aggregateByMethod.isEmpty()) {
            return List.of();
        }

        List<RuleMethodCardJson> cards = new ArrayList<>();
        for (MethodSignalAggregate aggregate : aggregateByMethod.values()) {
            SymbolEntity symbol = aggregate.symbol();
            String methodFqn = symbol.getQualifiedName();
            if (methodFqn == null || methodFqn.isBlank()) {
                continue;
            }

            List<RuleCandidate> methodCandidates =
                    candidatesByMethodSymbolId.getOrDefault(symbol.getSymbolId(), List.of());

            String classFqn = extractOwnerFqn(methodFqn);
            String methodName = extractMethodName(methodFqn);

            String sourceFile = symbol.getSourceFile() == null ? null : symbol.getSourceFile().getPath();
            Integer startLine = symbol.getSourceStartLine() == null
                    ? aggregate.minStartLine()
                    : symbol.getSourceStartLine();
            Integer endLine = symbol.getSourceEndLine() == null
                    ? aggregate.maxEndLine()
                    : symbol.getSourceEndLine();

            String summaryHint = buildSummaryHint(methodName, aggregate, methodCandidates);
            String scenarioHint = inferScenarioHint(methodName, aggregate);
            int importance = calculateImportance(aggregate, methodCandidates);

            cards.add(RuleMethodCardJson.builder()
                    .methodFqn(methodFqn)
                    .methodSymbolId(symbol.getSymbolId())
                    .classFqn(classFqn)
                    .methodName(methodName)
                    .sourceFile(sourceFile)
                    .startLine(startLine)
                    .endLine(endLine)
                    .summaryHint(summaryHint)
                    .scenarioHint(scenarioHint)
                    .importance(importance)
                    .signalCount(aggregate.signalCount())
                    .signalTypes(aggregate.sortedSignalTypes())
                    .avgConfidenceHint(aggregate.avgConfidenceHint())
                    .build());
        }

        cards.sort(
                Comparator.comparing(RuleMethodCardJson::importance, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RuleMethodCardJson::signalCount, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RuleMethodCardJson::methodFqn, Comparator.nullsLast(String::compareTo))
        );

        if (cards.size() > MAX_METHOD_CARDS) {
            return List.copyOf(cards.subList(0, MAX_METHOD_CARDS));
        }
        return List.copyOf(cards);
    }

    /**
     * 메서드 카드의 summary_hint를 생성한다.
     * - 1순위: 해당 메서드의 최고 점수 RuleCandidate 설명
     * - 2순위: signal 패턴 기반 기본 설명 문장
     */
    private String buildSummaryHint(
            String methodName,
            MethodSignalAggregate aggregate,
            List<RuleCandidate> methodCandidates
    ) {
        RuleCandidate bestCandidate = methodCandidates.stream()
                .sorted(Comparator
                        .comparing(RuleCandidate::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RuleCandidate::getCandidateId, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElse(null);

        if (bestCandidate != null) {
            String fromCandidate = firstNonBlank(bestCandidate.getDescription(), bestCandidate.getTitle()).trim();
            if (!fromCandidate.isBlank()) {
                return fromCandidate;
            }
        }

        return switch (inferPrimaryBehavior(aggregate)) {
            case "GUARD_THROW" -> "입력/상태 검증 후 예외를 던지는 방어 로직이 중심인 메서드입니다.";
            case "GUARD_RETURN" -> "조건 검사 후 조기 반환으로 흐름을 제어하는 메서드입니다.";
            case "PERSISTENCE" -> "저장소 save/update/delete 호출을 포함한 영속화 변경 메서드입니다.";
            case "ASSERTION" -> "require/assert 계열의 사전조건 검증을 수행하는 메서드입니다.";
            default -> {
                String safeName = methodName == null || methodName.isBlank() ? "핵심 메서드" : methodName;
                yield safeName + " 동작과 관련된 핵심 신호가 수집된 메서드입니다.";
            }
        };
    }

    /**
     * 메서드 카드에서 시나리오 라벨로 쓸 짧은 힌트를 만든다.
     */
    private String inferScenarioHint(String methodName, MethodSignalAggregate aggregate) {
        return switch (inferPrimaryBehavior(aggregate)) {
            case "GUARD_THROW" -> "잘못된 입력 처리 시나리오";
            case "GUARD_RETURN" -> "검증 실패 조기 종료 시나리오";
            case "PERSISTENCE" -> "데이터 변경/저장 시나리오";
            case "ASSERTION" -> "사전조건 검증 시나리오";
            default -> {
                String safeName = methodName == null || methodName.isBlank() ? "핵심 로직" : methodName;
                yield safeName + " 호출 시나리오";
            }
        };
    }

    /**
     * 메서드 카드 중요도를 계산한다.
     * - signal 밀도 + 연결된 후보 점수 + 행위 유형 보너스를 합산한다.
     */
    private int calculateImportance(MethodSignalAggregate aggregate, List<RuleCandidate> methodCandidates) {
        int signalScore = Math.min(12, aggregate.signalCount());
        int candidateScore = methodCandidates.stream()
                .map(RuleCandidate::getScore)
                .filter(score -> score != null)
                .map(score -> (int) Math.round(score.doubleValue() * 10.0d))
                .max(Integer::compareTo)
                .orElse(0);

        int behaviorBonus = switch (inferPrimaryBehavior(aggregate)) {
            case "GUARD_THROW", "GUARD_RETURN" -> 4;
            case "PERSISTENCE" -> 3;
            case "ASSERTION" -> 2;
            default -> 1;
        };

        return Math.min(30, 6 + signalScore + candidateScore + behaviorBonus);
    }

    /**
     * 수집된 signal 패턴을 기반으로 메서드의 대표 행위를 분류한다.
     */
    private String inferPrimaryBehavior(MethodSignalAggregate aggregate) {
        boolean hasGuardThrow = hasSignalType(aggregate, "IF_CONDITION") && hasSignalType(aggregate, "THROW_STATEMENT");
        if (hasGuardThrow) {
            return "GUARD_THROW";
        }

        boolean hasGuardReturn = hasSignalType(aggregate, "IF_CONDITION")
                && (hasSignalType(aggregate, "RETURN_STATEMENT") || hasSignalType(aggregate, "ERROR_RESPONSE"));
        if (hasGuardReturn) {
            return "GUARD_RETURN";
        }

        boolean hasPersistence = hasSignalType(aggregate, "REPOSITORY_SAVE")
                || hasSignalType(aggregate, "REPOSITORY_UPDATE")
                || hasSignalType(aggregate, "REPOSITORY_DELETE");
        if (hasPersistence) {
            return "PERSISTENCE";
        }

        boolean hasAssertion = hasSignalType(aggregate, "ASSERTION_CALL")
                || hasSignalType(aggregate, "REQUIRE_CALL");
        if (hasAssertion) {
            return "ASSERTION";
        }
        return "GENERAL";
    }

    /**
     * 특정 signal type이 한 번이라도 등장했는지 확인한다.
     */
    private boolean hasSignalType(MethodSignalAggregate aggregate, String signalType) {
        if (aggregate == null || signalType == null) {
            return false;
        }
        return aggregate.signalTypeCount(signalType) > 0;
    }

    /**
     * REPOSITORY_SAVE/UPDATE/DELETE 등 영속화 변경 신호인지 판별한다.
     * 영속 프레임워크 미사용 프로젝트에서 이름 패턴 오탐을 걸러내는 데 사용한다.
     */
    private boolean isPersistenceSignal(RuleMiningSignalType signalType) {
        return signalType == RuleMiningSignalType.REPOSITORY_SAVE
                || signalType == RuleMiningSignalType.REPOSITORY_UPDATE
                || signalType == RuleMiningSignalType.REPOSITORY_DELETE;
    }

    /**
     * symbol이 METHOD/CONSTRUCTOR인지 판별한다.
     */
    private boolean isMethodSymbol(SymbolEntity symbol) {
        if (symbol == null || symbol.getSymbolKind() == null) {
            return false;
        }
        return symbol.getSymbolKind() == SymbolKind.METHOD
                || symbol.getSymbolKind() == SymbolKind.CONSTRUCTOR;
    }

    /**
     * method FQN에서 owner class FQN을 추출한다.
     * 예) ctor:a.b.C#<init>(a.b.D) -> a.b.C
     * 예) method:a.b.C#m(int,String) -> a.b.C
     */
    private String extractOwnerFqn(String methodFqn) {
        if (methodFqn == null || methodFqn.isBlank()) {
            return "";
        }
        // "ctor:", "method:", "field:" 등 kind prefix 제거
        String fqn = methodFqn;
        int colonIdx = fqn.indexOf(':');
        if (colonIdx >= 0) {
            fqn = fqn.substring(colonIdx + 1);
        }
        // '#' 기준으로 분리 — '#' 앞이 owner class FQN
        int hashIdx = fqn.indexOf('#');
        if (hashIdx <= 0) {
            return fqn;
        }
        return fqn.substring(0, hashIdx);
    }

    /**
     * method FQN에서 method 이름을 추출한다.
     * 예) ctor:a.b.C#<init>(a.b.D) -> <init>
     * 예) method:a.b.C#m(int,String) -> m
     */
    private String extractMethodName(String methodFqn) {
        if (methodFqn == null || methodFqn.isBlank()) {
            return "";
        }
        // '#' 기준 분리 — '#' 뒤가 메서드 이름(파라미터 포함)
        int hashIdx = methodFqn.indexOf('#');
        if (hashIdx < 0 || hashIdx + 1 >= methodFqn.length()) {
            return "";
        }
        String afterHash = methodFqn.substring(hashIdx + 1);
        // '(' 이후 파라미터 제거
        int parenIdx = afterHash.indexOf('(');
        if (parenIdx >= 0) {
            return afterHash.substring(0, parenIdx);
        }
        return afterHash;
    }

    /**
     * 여러 문자열 중 비어있지 않은 첫 값을 반환한다.
     */
    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static class MethodSignalAggregate {
        private final SymbolEntity symbol;
        private final Map<String, Integer> signalTypeCounts = new HashMap<>();
        private int signalCount = 0;
        private BigDecimal confidenceSum = BigDecimal.ZERO;
        private int confidenceCount = 0;
        private Integer minStartLine;
        private Integer maxEndLine;

        private MethodSignalAggregate(SymbolEntity symbol) {
            this.symbol = symbol;
        }

        /**
         * 같은 메서드로 수집된 signal을 누적한다.
         * - 타입 빈도, 라인 범위, confidence 평균 계산용 합계를 함께 업데이트한다.
         */
        private void add(RuleMiningSignal signal) {
            signalCount++;
            String typeName = signal.getSignalType() == null ? "UNKNOWN" : signal.getSignalType().name();
            signalTypeCounts.merge(typeName, 1, Integer::sum);

            if (signal.getConfidenceHint() != null) {
                confidenceSum = confidenceSum.add(signal.getConfidenceHint());
                confidenceCount++;
            }
            if (signal.getStartLine() != null) {
                minStartLine = minStartLine == null ? signal.getStartLine() : Math.min(minStartLine, signal.getStartLine());
            }
            if (signal.getEndLine() != null) {
                maxEndLine = maxEndLine == null ? signal.getEndLine() : Math.max(maxEndLine, signal.getEndLine());
            }
        }

        private SymbolEntity symbol() {
            return symbol;
        }

        private int signalCount() {
            return signalCount;
        }

        private int signalTypeCount(String signalType) {
            return signalTypeCounts.getOrDefault(signalType, 0);
        }

        /**
         * signal type 빈도 내림차순으로 정렬된 목록을 반환한다.
         */
        private List<String> sortedSignalTypes() {
            return signalTypeCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        private Integer minStartLine() {
            return minStartLine;
        }

        private Integer maxEndLine() {
            return maxEndLine;
        }

        /**
         * 누적된 confidence 힌트의 평균을 계산한다.
         */
        private BigDecimal avgConfidenceHint() {
            if (confidenceCount == 0) {
                return null;
            }
            return confidenceSum.divide(BigDecimal.valueOf(confidenceCount), 4, RoundingMode.HALF_UP);
        }
    }

    /**
     * symbol_source_index 한 행을 만들기 위한 중간 집계 객체.
     */
    private static class SymbolCoverageAggregate {
        private final SymbolEntity symbol;
        private final Set<Long> evidenceIds = new HashSet<>();
        private final Set<RuleMiningSignalType> signalTypes = new HashSet<>();
        private BigDecimal confidenceHintSum = BigDecimal.ZERO;
        private int confidenceHintCount = 0;
        private Integer minStartLine;
        private Integer maxEndLine;

        private SymbolCoverageAggregate(SymbolEntity symbol) {
            this.symbol = symbol;
        }

        private SymbolEntity symbol() {
            return symbol;
        }

        private void addEvidenceId(Long evidenceId) {
            if (evidenceId != null) {
                evidenceIds.add(evidenceId);
            }
        }

        private void addSignalType(RuleMiningSignalType signalType) {
            if (signalType != null) {
                signalTypes.add(signalType);
            }
        }

        private void addSignalConfidence(BigDecimal hint) {
            if (hint == null) {
                return;
            }
            confidenceHintSum = confidenceHintSum.add(hint);
            confidenceHintCount++;
        }

        private void addLineRange(Integer startLine, Integer endLine) {
            if (startLine != null) {
                minStartLine = minStartLine == null ? startLine : Math.min(minStartLine, startLine);
            }
            if (endLine != null) {
                maxEndLine = maxEndLine == null ? endLine : Math.max(maxEndLine, endLine);
            }
        }

        private Integer minStartLine() {
            return minStartLine;
        }

        private Integer maxEndLine() {
            return maxEndLine;
        }

        private List<Long> sortedEvidenceIds() {
            return evidenceIds.stream().sorted().toList();
        }

        /**
         * 근거 커버리지 + signal confidence를 결합해 0~1 confidence를 계산한다.
         */
        private BigDecimal toConfidenceScore(String sourceFile, Integer startLine, Integer endLine) {
            BigDecimal score = new BigDecimal("0.10");

            boolean hasFile = sourceFile != null && !sourceFile.isBlank();
            boolean hasSpan = startLine != null && endLine != null;
            if (hasFile && hasSpan) {
                score = score.add(new BigDecimal("0.35"));
            } else if (hasFile) {
                score = score.add(new BigDecimal("0.20"));
            }

            int evidenceCount = evidenceIds.size();
            if (evidenceCount > 0) {
                score = score.add(new BigDecimal(Math.min(0.30d, evidenceCount * 0.03d)));
            }

            if (confidenceHintCount > 0) {
                BigDecimal avgHint = confidenceHintSum.divide(
                        BigDecimal.valueOf(confidenceHintCount),
                        4,
                        RoundingMode.HALF_UP
                );
                score = score.add(avgHint.multiply(new BigDecimal("0.30")));
            } else if (!signalTypes.isEmpty()) {
                score = score.add(new BigDecimal("0.10"));
            }

            if (score.compareTo(BigDecimal.ONE) > 0) {
                score = BigDecimal.ONE;
            }
            if (score.compareTo(new BigDecimal("0.05")) < 0) {
                score = new BigDecimal("0.05");
            }
            return score.setScale(4, RoundingMode.HALF_UP);
        }
    }

    private enum DisplayTier {
        SMALL(10, 14),
        MEDIUM(14, 22),
        LARGE(22, 35),
        XLARGE(30, 45);

        private final int targetMin;
        private final int targetMax;

        DisplayTier(int targetMin, int targetMax) {
            this.targetMin = targetMin;
            this.targetMax = targetMax;
        }

        int targetMin() {
            return targetMin;
        }

        int targetMax() {
            return targetMax;
        }

        static DisplayTier byIndex(int index) {
            DisplayTier[] values = DisplayTier.values();
            int bounded = Math.max(0, Math.min(values.length - 1, index));
            return values[bounded];
        }
    }

    private record CandidateQuality(boolean estimated, String reason) {
    }
}
