package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * LLM 입력 컨텍스트를 runId 기준으로 자동 조립한다.
 * - 사용자가 구조 데이터를 직접 주지 않아도 파이프라인 산출물 기반으로 입력을 구성한다.
 */
@Service
@RequiredArgsConstructor
public class LlmInputAssemblerService {

    // 토큰 과다 사용을 줄이기 위한 상한값들
    private static final int MAX_AUTO_EVIDENCE = 36;
    private static final int MAX_SNIPPET_LENGTH = 160;
    private static final int MAX_RULE_CANDIDATES_FOR_LLM = 28;
    private static final int MAX_EVIDENCE_PER_CANDIDATE = 2;
    private static final int MAX_CLASS_DIAGRAM_NODES = 40;
    private static final int MAX_CLASS_DIAGRAM_EDGES = 80;
    private static final int MAX_CLASS_DIAGRAM_EVIDENCE_SAMPLES = 1;
    private static final int MAX_RANKED_SYMBOLS = 40;
    private static final int MAX_RANKED_SUBSYSTEMS = 20;
    private static final int MAX_SUBSYSTEMS = 20;
    private static final int MAX_SYMBOL_IDS_PER_SUBSYSTEM = 8;
    private static final int MAX_PACKAGE_ROOTS_PER_SUBSYSTEM = 4;
    private static final int MAX_FILE_TREE_TYPES = 48;
    private static final int MAX_METHODS_PER_TYPE = 6;

    private final ArtifactRepository artifactRepository;
    private final SymbolRepository symbolRepository;
    private final ObjectMapper objectMapper;

    /**
     * LLM 실행에 필요한 구조 컨텍스트와 근거 번들을 반환한다.
     */
    public LlmContextBundle assemble(LlmRequest request) {
        if (!request.useAutoAssemble()) {
            return fromManualRequest(request);
        }

        try {
            ObjectNode structure = buildAutoStructure(request.getRunId(), request.useKorean());
            List<LlmRequest.EvidenceSnippet> evidenceBundle = resolveEvidenceBundle(
                    request,
                    structure.path("pipeline").path("ruleCandidates"),
                    structure.path("pipeline").path("classDiagram")
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> structureMap = objectMapper.convertValue(structure, Map.class);
            return new LlmContextBundle(structureMap, evidenceBundle);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(LlmErrorCode.CONTEXT_ASSEMBLE_FAILED);
        }
    }

    /**
     * 자동 조립을 사용하지 않는 경우 요청 본문의 수동 입력을 그대로 사용한다.
     */
    private LlmContextBundle fromManualRequest(LlmRequest request) {
        if (request.getStructureEngineOutput() == null || request.getStructureEngineOutput().isEmpty()) {
            throw new LlmException(LlmErrorCode.CONTEXT_ASSEMBLE_FAILED);
        }
        List<LlmRequest.EvidenceSnippet> evidence = request.getEvidenceBundle() == null
                ? List.of()
                : request.getEvidenceBundle();
        return new LlmContextBundle(request.getStructureEngineOutput(), evidence);
    }

    /**
     * 파이프라인 산출물을 묶어 LLM용 구조 컨텍스트 JSON을 구성한다.
     */
    private ObjectNode buildAutoStructure(String runId, boolean preferKorean) {
        JsonNode buildManifest = requireArtifactMeta(runId, ArtifactKind.BUILD_MANIFEST);
        JsonNode facts = requireArtifactMeta(runId, ArtifactKind.FACTS_JSON);
        JsonNode ruleCandidates = requireArtifactMeta(runId, ArtifactKind.RULE_CANDIDATES_JSON);

        JsonNode jobManifest = loadOptionalArtifactMeta(runId, ArtifactKind.JOB_MANIFEST);
        JsonNode subsystems = loadOptionalArtifactMeta(runId, ArtifactKind.SUBSYSTEMS_JSON);
        JsonNode rankings = loadOptionalArtifactMeta(runId, ArtifactKind.RANKINGS_JSON);
        JsonNode classDiagram = loadOptionalArtifactMeta(runId, ArtifactKind.CLASS_DIAGRAM_JSON);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("runId", runId);
        root.put("generatedAt", OffsetDateTime.now().toString());
        root.put("language", preferKorean ? "ko-KR" : "en-US");

        // LLM에는 원본 전체 대신 요약본만 전달해서 입력 토큰을 강하게 절감한다.
        ObjectNode pipeline = root.putObject("pipeline");
        pipeline.set("jobManifest", nullSafe(compactJobManifest(jobManifest)));
        pipeline.set("buildManifest", nullSafe(compactBuildManifest(buildManifest)));
        pipeline.set("ruleCandidates", nullSafe(compactRuleCandidates(ruleCandidates)));
        pipeline.set("subsystems", nullSafe(compactSubsystems(subsystems)));
        pipeline.set("rankings", nullSafe(compactRankings(rankings)));
        pipeline.set("classDiagram", nullSafe(compactClassDiagram(classDiagram)));

        root.set("qualityGate", buildQualityGate(buildManifest, facts));
        root.set("displayHints", buildDisplayHints(ruleCandidates, classDiagram));
        root.set("fileTreeSeed", buildFileTreeSeed(runId, classDiagram));
        return root;
    }

    private JsonNode compactJobManifest(JsonNode jobManifest) {
        if (jobManifest == null || jobManifest.isMissingNode() || jobManifest.isNull()) {
            return null;
        }

        ObjectNode compact = objectMapper.createObjectNode();
        copyTextField(compact, jobManifest, "runId", "run_id");
        copyTextField(compact, jobManifest, "repoUrl", "repo_url", "repositoryUrl", "repository_url");
        copyTextField(compact, jobManifest, "repoName", "repo_name");
        copyTextField(compact, jobManifest, "branch", "defaultBranch", "default_branch");
        copyTextField(compact, jobManifest, "commitSha", "commit_sha", "sha");
        copyTextField(compact, jobManifest, "snapshotMode", "snapshot_mode");
        copyLimitedArray(compact, jobManifest, "modules", 30);
        copyLimitedArray(compact, jobManifest, "samplePaths", 20);
        return compact;
    }

    private JsonNode compactBuildManifest(JsonNode buildManifest) {
        if (buildManifest == null || buildManifest.isMissingNode() || buildManifest.isNull()) {
            return null;
        }

        ObjectNode compact = objectMapper.createObjectNode();
        copyTextField(compact, buildManifest, "buildTool", "build_tool", "tool");
        copyTextField(compact, buildManifest, "buildMode", "build_mode", "mode");
        copyTextField(compact, buildManifest, "status");
        copyTextField(compact, buildManifest, "javaVersion", "java_version");
        copyTextField(compact, buildManifest, "failureReason", "failure_reason");
        copyLimitedArray(compact, buildManifest, "warnings", 8);
        return compact;
    }

    private JsonNode compactFacts(JsonNode facts) {
        if (facts == null || facts.isMissingNode() || facts.isNull()) {
            return null;
        }

        ObjectNode compact = objectMapper.createObjectNode();
        copyTextField(compact, facts, "schema_version", "schemaVersion");

        JsonNode extraction = facts.path("extraction");
        ObjectNode extractionCompact = objectMapper.createObjectNode();
        copyTextField(extractionCompact, extraction, "mode");
        copyTextField(extractionCompact, extraction, "bytecode_availability", "bytecodeAvailability");
        copyLimitedArray(extractionCompact, extraction, "warnings", 5);
        copyLimitedArray(extractionCompact, extraction, "scanned_modules", 10);
        copyLimitedArray(extractionCompact, extraction, "scanned_source_roots", 10);
        copyLimitedArray(extractionCompact, extraction, "scanned_bytecode_roots", 10);
        compact.set("extraction", extractionCompact);

        JsonNode stats = facts.path("stats");
        if (!stats.isMissingNode() && !stats.isNull()) {
            compact.set("stats", stats);
        }
        return compact;
    }

    private JsonNode compactRuleCandidates(JsonNode ruleCandidates) {
        if (ruleCandidates == null || ruleCandidates.isMissingNode() || ruleCandidates.isNull()) {
            return null;
        }

        ObjectNode compact = objectMapper.createObjectNode();
        copyTextField(compact, ruleCandidates, "schemaVersion", "schema_version");
        compact.set("summary", ruleCandidates.path("summary"));
        compact.set("displayPolicy", compactDisplayPolicy(ruleCandidates.path("displayPolicy")));

        Set<Long> primaryIds = extractLongIds(ruleCandidates.path("displayPolicy").path("primaryCandidateIds"));
        JsonNode candidates = ruleCandidates.path("candidates");

        ArrayNode selected = compact.putArray("candidates");
        int remain = MAX_RULE_CANDIDATES_FOR_LLM;
        // public API 연관 후보를 우선 선별해 출력 품질을 높이고 토큰을 절감한다.
        remain -= appendCompactRuleCandidates(candidates, primaryIds, true, true, remain, selected);
        if (remain > 0) {
            remain -= appendCompactRuleCandidates(candidates, primaryIds, false, true, remain, selected);
        }
        if (remain > 0) {
            remain -= appendCompactRuleCandidates(candidates, primaryIds, true, false, remain, selected);
        }
        if (remain > 0) {
            appendCompactRuleCandidates(candidates, primaryIds, false, false, remain, selected);
        }

        compact.put("selectedCandidateCount", selected.size());
        compact.put("originalCandidateCount", candidates.isArray() ? candidates.size() : 0);
        return compact;
    }

    private JsonNode compactDisplayPolicy(JsonNode displayPolicy) {
        if (displayPolicy == null || displayPolicy.isMissingNode() || displayPolicy.isNull()) {
            return objectMapper.createObjectNode();
        }

        ObjectNode compact = objectMapper.createObjectNode();
        copyTextField(compact, displayPolicy, "sizeTier");
        copyIntField(compact, displayPolicy, "totalCandidates", "recommendedPrimaryCount");
        copyLimitedArray(compact, displayPolicy, "primaryCandidateIds", MAX_RULE_CANDIDATES_FOR_LLM);
        copyLimitedArray(compact, displayPolicy, "exploratoryCandidateIds", 20);
        return compact;
    }

    private int appendCompactRuleCandidates(
            JsonNode candidates,
            Set<Long> primaryIds,
            boolean primaryPhase,
            boolean publicOnly,
            int remain,
            ArrayNode out
    ) {
        if (!candidates.isArray() || remain <= 0) {
            return 0;
        }

        int added = 0;
        for (JsonNode candidate : candidates) {
            if (added >= remain) {
                break;
            }

            long candidateId = candidate.path("candidateId").asLong(-1L);
            boolean isPrimary = primaryIds.isEmpty() || primaryIds.contains(candidateId);
            if (primaryPhase != isPrimary) {
                continue;
            }
            boolean publicApiRelated = candidate.path("publicApiRelated").asBoolean(false);
            if (publicOnly && !publicApiRelated) {
                continue;
            }
            if (!primaryPhase && !"CONFIRMED".equalsIgnoreCase(candidate.path("qualityLabel").asText(""))) {
                continue;
            }

            out.add(compactRuleCandidate(candidate, isPrimary));
            added++;
        }
        return added;
    }

    private ObjectNode compactRuleCandidate(JsonNode candidate, boolean primary) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyLongField(compact, candidate, "candidateId");
        copyTextField(compact, candidate, "ruleKey", "candidateKind", "status");
        copyTextField(compact, candidate, "subjectQualifiedName", "groupId");
        copyDecimalOrNumberField(compact, candidate, "score");
        copyIntField(compact, candidate, "supportCount", "evidenceCount");
        copyBooleanField(compact, candidate, "publicApiRelated", "estimated");
        copyTextField(compact, candidate, "qualityLabel", "qualityReason");
        compact.put("primary", primary);

        String title = shortenText(candidate.path("title").asText(""), 120);
        if (!title.isBlank()) {
            compact.put("title", title);
        }
        String description = shortenText(candidate.path("description").asText(""), 220);
        if (!description.isBlank()) {
            compact.put("description", description);
        }

        if (!candidate.path("summary").isMissingNode() && !candidate.path("summary").isNull()) {
            String summary = shortenText(candidate.path("summary").asText(""), 180);
            if (!summary.isBlank()) {
                compact.put("summary", summary);
            }
        }

        ArrayNode evidences = compact.putArray("evidences");
        JsonNode sourceEvidences = candidate.path("evidences");
        if (sourceEvidences.isArray()) {
            int count = 0;
            for (JsonNode evidence : sourceEvidences) {
                if (count >= MAX_EVIDENCE_PER_CANDIDATE) {
                    break;
                }
                ObjectNode evidenceNode = objectMapper.createObjectNode();
                copyLongField(evidenceNode, evidence, "evidenceId", "signalId", "edgeId");
                copyTextField(evidenceNode, evidence, "role", "filePath");
                copyIntField(evidenceNode, evidence, "startLine", "endLine");
                String snippet = trimSnippet(evidence.path("snippet").asText(null));
                if (snippet != null) {
                    evidenceNode.put("snippet", snippet);
                }
                evidences.add(evidenceNode);
                count++;
            }
        }
        return compact;
    }

    private JsonNode compactClassDiagram(JsonNode classDiagram) {
        if (classDiagram == null || classDiagram.isMissingNode() || classDiagram.isNull()) {
            return null;
        }

        ObjectNode compact = objectMapper.createObjectNode();
        compact.set("summary", classDiagram.path("summary"));
        compact.set("displayPolicy", classDiagram.path("displayPolicy"));

        ArrayNode nodesOut = compact.putArray("nodes");
        JsonNode nodes = classDiagram.path("nodes");
        if (nodes.isArray()) {
            for (int i = 0; i < nodes.size() && i < MAX_CLASS_DIAGRAM_NODES; i++) {
                JsonNode node = nodes.get(i);
                ObjectNode nodeOut = objectMapper.createObjectNode();
                copyTextField(nodeOut, node, "symbolId", "label", "qualifiedName", "packageName", "access");
                copyDecimalOrNumberField(nodeOut, node, "score");
                copyLimitedArray(nodeOut, node, "badges", 3);
                copyLimitedArray(nodeOut, node, "reasons", 2);
                nodesOut.add(nodeOut);
            }
        }

        ArrayNode edgesOut = compact.putArray("edges");
        JsonNode edges = classDiagram.path("edges");
        if (edges.isArray()) {
            for (int i = 0; i < edges.size() && i < MAX_CLASS_DIAGRAM_EDGES; i++) {
                JsonNode edge = edges.get(i);
                ObjectNode edgeOut = objectMapper.createObjectNode();
                copyTextField(edgeOut, edge, "sourceSymbolId", "targetSymbolId", "edgeType", "label", "resolution");
                copyIntField(edgeOut, edge, "evidenceCount");
                copyDecimalOrNumberField(edgeOut, edge, "confidence");
                copyLimitedArray(edgeOut, edge, "badges", 2);

                JsonNode evidence = edge.path("evidence");
                if (!evidence.isMissingNode() && !evidence.isNull()) {
                    ObjectNode evidenceOut = objectMapper.createObjectNode();
                    copyLimitedArray(evidenceOut, evidence, "evidenceTypes", 2);
                    ArrayNode samplesOut = evidenceOut.putArray("samples");
                    JsonNode samples = evidence.path("samples");
                    if (samples.isArray()) {
                        for (int sampleIdx = 0;
                             sampleIdx < samples.size() && sampleIdx < MAX_CLASS_DIAGRAM_EVIDENCE_SAMPLES;
                             sampleIdx++) {
                            JsonNode sample = samples.get(sampleIdx);
                            ObjectNode sampleOut = objectMapper.createObjectNode();
                            copyTextField(sampleOut, sample, "filePath");
                            copyIntField(sampleOut, sample, "startLine", "endLine");
                            String snippet = trimSnippet(sample.path("snippet").asText(null));
                            if (snippet != null) {
                                sampleOut.put("snippet", snippet);
                            }
                            samplesOut.add(sampleOut);
                        }
                    }
                    edgeOut.set("evidence", evidenceOut);
                }
                edgesOut.add(edgeOut);
            }
        }

        compact.put("selectedNodeCount", nodesOut.size());
        compact.put("selectedEdgeCount", edgesOut.size());
        return compact;
    }

    private JsonNode compactRankings(JsonNode rankings) {
        if (rankings == null || rankings.isMissingNode() || rankings.isNull()) {
            return null;
        }

        ObjectNode compact = objectMapper.createObjectNode();
        copyTextField(compact, rankings, "schemaVersion", "schema_version", "runId", "run_id");

        ArrayNode symbolRankingsOut = compact.putArray("symbolRankings");
        JsonNode symbolRankings = rankings.path("symbolRankings");
        if (symbolRankings.isArray()) {
            for (int i = 0; i < symbolRankings.size() && i < MAX_RANKED_SYMBOLS; i++) {
                JsonNode symbol = symbolRankings.get(i);
                ObjectNode symbolOut = objectMapper.createObjectNode();
                copyIntField(symbolOut, symbol, "rank");
                copyTextField(symbolOut, symbol, "symbolId", "qualifiedName", "subsystemId");
                copyDecimalOrNumberField(symbolOut, symbol, "score");
                symbolRankingsOut.add(symbolOut);
            }
        }

        ArrayNode subsystemRankingsOut = compact.putArray("subsystemRankings");
        JsonNode subsystemRankings = rankings.path("subsystemRankings");
        if (subsystemRankings.isArray()) {
            for (int i = 0; i < subsystemRankings.size() && i < MAX_RANKED_SUBSYSTEMS; i++) {
                JsonNode subsystem = subsystemRankings.get(i);
                ObjectNode subsystemOut = objectMapper.createObjectNode();
                copyIntField(subsystemOut, subsystem, "rank");
                copyTextField(subsystemOut, subsystem, "subsystemId", "name");
                copyDecimalOrNumberField(subsystemOut, subsystem, "score");
                subsystemRankingsOut.add(subsystemOut);
            }
        }

        compact.put("selectedSymbolRankCount", symbolRankingsOut.size());
        compact.put("selectedSubsystemRankCount", subsystemRankingsOut.size());
        return compact;
    }

    private JsonNode compactSubsystems(JsonNode subsystems) {
        if (subsystems == null || subsystems.isMissingNode() || subsystems.isNull()) {
            return null;
        }

        ObjectNode compact = objectMapper.createObjectNode();
        copyTextField(compact, subsystems, "schemaVersion", "schema_version", "runId", "run_id");
        if (!subsystems.path("algorithm").isMissingNode() && !subsystems.path("algorithm").isNull()) {
            compact.set("algorithm", subsystems.path("algorithm"));
        }

        ArrayNode subsystemsOut = compact.putArray("subsystems");
        JsonNode sourceSubsystems = subsystems.path("subsystems");
        if (sourceSubsystems.isArray()) {
            for (int i = 0; i < sourceSubsystems.size() && i < MAX_SUBSYSTEMS; i++) {
                JsonNode subsystem = sourceSubsystems.get(i);
                ObjectNode out = objectMapper.createObjectNode();
                copyTextField(out, subsystem, "subsystemId", "name");
                copyDecimalOrNumberField(out, subsystem, "score");

                JsonNode members = subsystem.path("memberSymbolIds");
                out.put("memberCount", members.isArray() ? members.size() : 0);
                copyLimitedArray(out, subsystem, "entrySymbolIds", MAX_SYMBOL_IDS_PER_SUBSYSTEM);
                copyLimitedArray(out, subsystem, "coreSymbolIds", MAX_SYMBOL_IDS_PER_SUBSYSTEM);
                copyLimitedArray(out, subsystem, "packageRoots", MAX_PACKAGE_ROOTS_PER_SUBSYSTEM);
                subsystemsOut.add(out);
            }
        }
        compact.put("selectedSubsystemCount", subsystemsOut.size());
        return compact;
    }

    /**
     * LLM이 해석하기 쉽도록 빌드/추출 품질 상태를 요약한 품질 게이트 정보를 생성한다.
     */
    private ObjectNode buildQualityGate(JsonNode buildManifest, JsonNode facts) {
        ObjectNode quality = objectMapper.createObjectNode();

        String buildMode = textFromCandidates(buildManifest, "buildMode", "build_mode");
        String buildTool = textFromCandidates(buildManifest, "buildTool", "build_tool");
        quality.put("buildMode", buildMode == null ? "UNKNOWN" : buildMode);
        quality.put("buildTool", buildTool == null ? "UNKNOWN" : buildTool);

        JsonNode extraction = facts.path("extraction");
        quality.put("extractionMode", textFromCandidates(extraction, "mode"));
        quality.put("bytecodeAvailability", textFromCandidates(extraction, "bytecode_availability", "bytecodeAvailability"));

        JsonNode stats = facts.path("stats");
        long unresolvedTypeRefs = stats.path("unresolved_type_refs").asLong(0L);
        long relationCount = stats.path("relations").asLong(0L);
        long errorCount = stats.path("errors").asLong(0L);

        double unresolvedRate = relationCount <= 0L ? 0.0 : ((double) unresolvedTypeRefs / (double) relationCount);
        quality.put("unresolvedTypeRefCount", unresolvedTypeRefs);
        quality.put("relationCount", relationCount);
        quality.put("errorCount", errorCount);
        quality.put("unresolvedTypeRefRate", round3(unresolvedRate));

        ArrayNode warnings = quality.putArray("warnings");
        JsonNode extractionWarnings = extraction.path("warnings");
        if (extractionWarnings.isArray()) {
            for (JsonNode warning : extractionWarnings) {
                if (warning != null && warning.isTextual() && !warning.asText().isBlank()) {
                    warnings.add(warning.asText());
                }
            }
        }

        return quality;
    }

    /**
     * 룰/클래스맵 표시 정책 요약을 LLM에 전달해 문장 생성 시 참고하도록 한다.
     */
    private ObjectNode buildDisplayHints(JsonNode ruleCandidates, JsonNode classDiagram) {
        ObjectNode hints = objectMapper.createObjectNode();
        hints.set("ruleDisplayPolicy", ruleCandidates.path("displayPolicy"));
        hints.set("classDiagramSummary", classDiagram.path("summary"));
        hints.set("classDiagramPolicy", classDiagram.path("displayPolicy"));
        return hints;
    }

    /**
     * 파일 디렉터리 구조 기반 클래스/메서드 설명 생성을 위한 시드 데이터를 만든다.
     */
    private JsonNode buildFileTreeSeed(String runId, JsonNode classDiagram) {
        Set<String> selectedTypeIds = extractSelectedTypeIds(classDiagram);
        List<SymbolEntity> symbols = symbolRepository.findAllByRun_RunId(runId);

        Map<String, SymbolEntity> typeById = new HashMap<>();
        for (SymbolEntity symbol : symbols) {
            if (symbol.getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }
            if (!selectedTypeIds.isEmpty() && !selectedTypeIds.contains(symbol.getSymbolId())) {
                continue;
            }
            if (!isPublicSymbol(symbol)) {
                continue;
            }
            String typeFilePath = resolveRepoRelativePath(symbol);
            if (!isMainSourcePath(typeFilePath)) {
                continue;
            }
            typeById.put(symbol.getSymbolId(), symbol);
        }

        // 파일 트리도 상한을 적용해 Step 5 프롬프트 토큰을 제어한다.
        TreeMap<String, ObjectNode> fileNodeByPath = new TreeMap<>();
        Map<String, Map<String, ObjectNode>> classNodeByFileAndType = new HashMap<>();

        List<SymbolEntity> sortedTypes = new ArrayList<>(typeById.values());
        sortedTypes.sort(Comparator.comparing(type -> safeText(type.getQualifiedName())));

        int totalTypeCount = 0;
        for (SymbolEntity type : sortedTypes) {
            if (totalTypeCount >= MAX_FILE_TREE_TYPES) {
                break;
            }

            String filePath = resolveRepoRelativePath(type);
            if (filePath == null) {
                continue;
            }

            Map<String, ObjectNode> classMap = classNodeByFileAndType.computeIfAbsent(filePath, ignored -> new LinkedHashMap<>());
            if (classMap.containsKey(type.getSymbolId())) {
                continue;
            }

            ensureClassNode(filePath, type, fileNodeByPath, classNodeByFileAndType);
            totalTypeCount++;
        }

        for (SymbolEntity symbol : symbols) {
            if (symbol.getSymbolKind() != SymbolKind.METHOD && symbol.getSymbolKind() != SymbolKind.CONSTRUCTOR) {
                continue;
            }
            SymbolEntity owner = symbol.getOwner();
            if (owner == null || owner.getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }
            if (!selectedTypeIds.isEmpty() && !selectedTypeIds.contains(owner.getSymbolId())) {
                continue;
            }
            if (!isPublicSymbol(owner) || !isPublicSymbol(symbol)) {
                continue;
            }

            String filePath = resolveRepoRelativePath(symbol);
            if (filePath == null) {
                filePath = resolveRepoRelativePath(owner);
            }
            if (!isMainSourcePath(filePath)) {
                continue;
            }

            Map<String, ObjectNode> classMap = classNodeByFileAndType.computeIfAbsent(filePath, ignored -> new LinkedHashMap<>());
            ObjectNode classNode = classMap.get(owner.getSymbolId());
            if (classNode == null) {
                if (totalTypeCount >= MAX_FILE_TREE_TYPES) {
                    continue;
                }
                classNode = ensureClassNode(filePath, owner, fileNodeByPath, classNodeByFileAndType);
                totalTypeCount++;
            }

            ArrayNode methods = (ArrayNode) classNode.withArray("methods");
            if (methods.size() >= MAX_METHODS_PER_TYPE) {
                continue;
            }

            ObjectNode methodNode = methods.addObject();
            methodNode.put("symbolId", safeText(symbol.getSymbolId()));
            methodNode.put("name", safeText(symbol.getSimpleName()));
            methodNode.put("qualifiedName", safeText(symbol.getQualifiedName()));
            methodNode.put("kind", symbol.getSymbolKind().name());
            methodNode.put("access", symbol.getAccess() == null ? "UNKNOWN" : symbol.getAccess().name());
            methodNode.put("startLine", symbol.getSourceStartLine() == null ? -1 : symbol.getSourceStartLine());
            methodNode.put("endLine", symbol.getSourceEndLine() == null ? -1 : symbol.getSourceEndLine());
        }

        TreeMap<String, List<ObjectNode>> filesByDirectory = new TreeMap<>();
        for (Map.Entry<String, ObjectNode> entry : fileNodeByPath.entrySet()) {
            String filePath = entry.getKey();
            String directoryPath = extractDirectoryPath(filePath);
            filesByDirectory.computeIfAbsent(directoryPath, ignored -> new ArrayList<>())
                    .add(entry.getValue());
        }

        ObjectNode seed = objectMapper.createObjectNode();
        seed.put("root", "repo");
        seed.put("scope", "public-api");
        seed.put("sourcePathPolicy", "src/main/java");
        seed.put("directoryCount", filesByDirectory.size());
        seed.put("fileCount", fileNodeByPath.size());

        ArrayNode directories = seed.putArray("directories");
        for (Map.Entry<String, List<ObjectNode>> directoryEntry : filesByDirectory.entrySet()) {
            ObjectNode dirNode = directories.addObject();
            dirNode.put("path", directoryEntry.getKey());
            ArrayNode files = dirNode.putArray("files");
            for (ObjectNode fileNode : directoryEntry.getValue()) {
                files.add(fileNode);
            }
        }
        return seed;
    }

    /**
     * 요청에 evidenceBundle이 있으면 우선 사용하고,
     * 없으면 rule_candidates/class_diagram 기반으로 자동 생성한다.
     */
    private List<LlmRequest.EvidenceSnippet> resolveEvidenceBundle(
            LlmRequest request,
            JsonNode ruleCandidates,
            JsonNode classDiagram
    ) {
        if (request.getEvidenceBundle() != null && !request.getEvidenceBundle().isEmpty()) {
            return trimEvidenceSnippets(request.getEvidenceBundle(), MAX_AUTO_EVIDENCE);
        }

        List<LlmRequest.EvidenceSnippet> collected = new ArrayList<>();
        Set<String> dedupeKeys = new HashSet<>();
        collectFromRuleCandidates(ruleCandidates, collected, dedupeKeys);
        if (collected.isEmpty()) {
            collectFromClassDiagram(classDiagram, collected, dedupeKeys);
        }

        return trimEvidenceSnippets(collected, MAX_AUTO_EVIDENCE);
    }

    private void collectFromRuleCandidates(
            JsonNode ruleCandidates,
            List<LlmRequest.EvidenceSnippet> out,
            Set<String> dedupeKeys
    ) {
        if (ruleCandidates == null || ruleCandidates.isMissingNode()) {
            return;
        }

        Set<Long> primaryIds = new HashSet<>();
        JsonNode primaryArray = ruleCandidates.path("displayPolicy").path("primaryCandidateIds");
        if (primaryArray.isArray()) {
            for (JsonNode idNode : primaryArray) {
                if (idNode != null && idNode.canConvertToLong()) {
                    primaryIds.add(idNode.asLong());
                }
            }
        }

        JsonNode candidates = ruleCandidates.path("candidates");
        if (!candidates.isArray()) {
            return;
        }

        for (JsonNode candidate : candidates) {
            if (out.size() >= MAX_AUTO_EVIDENCE) {
                break;
            }

            long candidateId = candidate.path("candidateId").asLong(-1L);
            boolean primary = primaryIds.isEmpty() || primaryIds.contains(candidateId);
            String qualityLabel = candidate.path("qualityLabel").asText("");
            if (!primary && !"CONFIRMED".equalsIgnoreCase(qualityLabel)) {
                continue;
            }

            JsonNode evidences = candidate.path("evidences");
            if (!evidences.isArray()) {
                continue;
            }

            int addedForCandidate = 0;
            for (JsonNode evidence : evidences) {
                if (out.size() >= MAX_AUTO_EVIDENCE || addedForCandidate >= MAX_EVIDENCE_PER_CANDIDATE) {
                    break;
                }
                String snippet = trimSnippet(evidence.path("snippet").asText(null));
                String filePath = evidence.path("filePath").asText(null);
                if (snippet == null || filePath == null || filePath.isBlank()) {
                    continue;
                }

                Integer startLine = asNullableInt(evidence.path("startLine"));
                Integer endLine = asNullableInt(evidence.path("endLine"));
                if (!dedupeKeys.add(buildEvidenceDedupeKey(filePath, startLine, endLine, snippet))) {
                    continue;
                }

                Long evidenceId = evidence.path("evidenceId").isMissingNode()
                        ? null
                        : evidence.path("evidenceId").asLong();

                out.add(new LlmRequest.EvidenceSnippet(
                        evidenceId,
                        filePath,
                        startLine,
                        endLine,
                        snippet,
                        evidence.path("role").asText("RULE_EVIDENCE")
                ));
                addedForCandidate++;
            }
        }
    }

    private void collectFromClassDiagram(
            JsonNode classDiagram,
            List<LlmRequest.EvidenceSnippet> out,
            Set<String> dedupeKeys
    ) {
        if (classDiagram == null || classDiagram.isMissingNode()) {
            return;
        }

        JsonNode edges = classDiagram.path("edges");
        if (!edges.isArray()) {
            return;
        }

        for (JsonNode edge : edges) {
            if (out.size() >= MAX_AUTO_EVIDENCE) {
                break;
            }

            JsonNode evidence = edge.path("evidence");
            JsonNode samples = evidence.path("samples");
            JsonNode evidenceTypes = evidence.path("evidenceTypes");
            String evidenceType = evidenceTypes.isArray() && !evidenceTypes.isEmpty()
                    ? evidenceTypes.get(0).asText("CLASS_DIAGRAM")
                    : "CLASS_DIAGRAM";

            if (!samples.isArray()) {
                continue;
            }

            int addedForEdge = 0;
            for (JsonNode sample : samples) {
                if (out.size() >= MAX_AUTO_EVIDENCE || addedForEdge >= MAX_CLASS_DIAGRAM_EVIDENCE_SAMPLES) {
                    break;
                }
                String snippet = trimSnippet(sample.path("snippet").asText(null));
                String filePath = sample.path("filePath").asText(null);
                if (snippet == null || filePath == null || filePath.isBlank()) {
                    continue;
                }

                Integer startLine = asNullableInt(sample.path("startLine"));
                Integer endLine = asNullableInt(sample.path("endLine"));
                if (!dedupeKeys.add(buildEvidenceDedupeKey(filePath, startLine, endLine, snippet))) {
                    continue;
                }

                out.add(new LlmRequest.EvidenceSnippet(
                        null,
                        filePath,
                        startLine,
                        endLine,
                        snippet,
                        evidenceType
                ));
                addedForEdge++;
            }
        }
    }

    private List<LlmRequest.EvidenceSnippet> trimEvidenceSnippets(
            List<LlmRequest.EvidenceSnippet> source,
            int limit
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        List<LlmRequest.EvidenceSnippet> result = new ArrayList<>();
        Set<String> dedupeKeys = new HashSet<>();
        for (LlmRequest.EvidenceSnippet item : source) {
            if (result.size() >= limit) {
                break;
            }
            if (item == null || item.getFilePath() == null || item.getSnippet() == null) {
                continue;
            }
            String trimmedSnippet = trimSnippet(item.getSnippet());
            if (trimmedSnippet == null) {
                continue;
            }
            if (!dedupeKeys.add(buildEvidenceDedupeKey(
                    item.getFilePath(),
                    item.getStartLine(),
                    item.getEndLine(),
                    trimmedSnippet
            ))) {
                continue;
            }

            result.add(new LlmRequest.EvidenceSnippet(
                    item.getEvidenceId(),
                    item.getFilePath(),
                    item.getStartLine(),
                    item.getEndLine(),
                    trimmedSnippet,
                    item.getEvidenceType()
            ));
        }
        return result;
    }

    private ObjectNode ensureClassNode(
            String filePath,
            SymbolEntity type,
            Map<String, ObjectNode> fileNodeByPath,
            Map<String, Map<String, ObjectNode>> classNodeByFileAndType
    ) {
        ObjectNode fileNode = fileNodeByPath.computeIfAbsent(filePath, path -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("path", path);
            node.putArray("classes");
            return node;
        });

        Map<String, ObjectNode> classMap = classNodeByFileAndType.computeIfAbsent(filePath, ignored -> new LinkedHashMap<>());
        ObjectNode classNode = classMap.get(type.getSymbolId());
        if (classNode != null) {
            return classNode;
        }

        classNode = objectMapper.createObjectNode();
        classNode.put("symbolId", safeText(type.getSymbolId()));
        classNode.put("name", safeText(type.getSimpleName()));
        classNode.put("qualifiedName", safeText(type.getQualifiedName()));
        classNode.put("access", type.getAccess() == null ? "UNKNOWN" : type.getAccess().name());
        classNode.put("startLine", type.getSourceStartLine() == null ? -1 : type.getSourceStartLine());
        classNode.put("endLine", type.getSourceEndLine() == null ? -1 : type.getSourceEndLine());
        classNode.putArray("methods");

        ((ArrayNode) fileNode.withArray("classes")).add(classNode);
        classMap.put(type.getSymbolId(), classNode);
        return classNode;
    }

    private Set<String> extractSelectedTypeIds(JsonNode classDiagram) {
        if (classDiagram == null || classDiagram.isMissingNode()) {
            return Set.of();
        }
        JsonNode nodes = classDiagram.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            String symbolId = node.path("symbolId").asText(null);
            if (symbolId != null && !symbolId.isBlank()) {
                ids.add(symbolId);
            }
        }
        return ids;
    }

    private String resolveRepoRelativePath(SymbolEntity symbol) {
        if (symbol == null || symbol.getSourceFile() == null || symbol.getSourceFile().getPath() == null) {
            return null;
        }

        String normalized = symbol.getSourceFile().getPath().replace('\\', '/');
        int repoIdx = normalized.indexOf("/repo/");
        if (repoIdx < 0) {
            return normalized;
        }

        String afterRepo = normalized.substring(repoIdx + "/repo/".length());
        int firstSlash = afterRepo.indexOf('/');
        if (firstSlash < 0 || firstSlash == afterRepo.length() - 1) {
            return afterRepo;
        }
        return afterRepo.substring(firstSlash + 1);
    }

    /**
     * file tree 문서화는 외부 확장에 의미가 있는 public 심볼만 포함한다.
     */
    private boolean isPublicSymbol(SymbolEntity symbol) {
        return symbol != null && symbol.getAccess() == AccessLevel.PUBLIC;
    }

    /**
     * Step5 입력은 배포/사용 관점의 주 경로(src/main/java)만 허용한다.
     */
    private boolean isMainSourcePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        String normalized = filePath.replace('\\', '/');
        return normalized.contains("src/main/java/");
    }

    private String extractDirectoryPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return ".";
        }
        int idx = filePath.lastIndexOf('/');
        if (idx < 0) {
            return ".";
        }
        return filePath.substring(0, idx);
    }

    private JsonNode requireArtifactMeta(String runId, ArtifactKind kind) {
        Artifact artifact = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, kind)
                .orElseThrow(() -> new LlmException(LlmErrorCode.REQUIRED_ARTIFACT_NOT_FOUND));

        if (artifact.getMeta() == null || artifact.getMeta().isNull()) {
            throw new LlmException(LlmErrorCode.REQUIRED_ARTIFACT_NOT_FOUND);
        }
        return artifact.getMeta();
    }

    private JsonNode loadOptionalArtifactMeta(String runId, ArtifactKind kind) {
        return artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, kind)
                .map(Artifact::getMeta)
                .orElse(null);
    }

    private String textFromCandidates(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private Integer asNullableInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.canConvertToInt() ? node.asInt() : null;
    }

    private Set<Long> extractLongIds(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (JsonNode item : arrayNode) {
            if (item != null && item.canConvertToLong()) {
                ids.add(item.asLong());
            }
        }
        return ids;
    }

    private JsonNode nullSafe(JsonNode node) {
        return node == null ? NullNode.getInstance() : node;
    }

    private void copyTextField(ObjectNode target, JsonNode source, String... keys) {
        if (target == null || source == null || keys == null) {
            return;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.isTextual() && !value.asText().isBlank()) {
                target.put(key, value.asText());
            }
        }
    }

    private void copyIntField(ObjectNode target, JsonNode source, String... keys) {
        if (target == null || source == null || keys == null) {
            return;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.canConvertToInt()) {
                target.put(key, value.asInt());
            }
        }
    }

    private void copyLongField(ObjectNode target, JsonNode source, String... keys) {
        if (target == null || source == null || keys == null) {
            return;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.canConvertToLong()) {
                target.put(key, value.asLong());
            }
        }
    }

    private void copyBooleanField(ObjectNode target, JsonNode source, String... keys) {
        if (target == null || source == null || keys == null) {
            return;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.isBoolean()) {
                target.put(key, value.asBoolean());
            }
        }
    }

    private void copyDecimalOrNumberField(ObjectNode target, JsonNode source, String... keys) {
        if (target == null || source == null || keys == null) {
            return;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isFloatingPointNumber()) {
                target.put(key, value.asDouble());
            } else if (value.isIntegralNumber()) {
                target.put(key, value.asLong());
            } else if (value.isTextual()) {
                target.put(key, value.asText());
            }
        }
    }

    private void copyLimitedArray(ObjectNode target, JsonNode source, String key, int maxCount) {
        if (target == null || source == null) {
            return;
        }
        JsonNode arr = source.get(key);
        if (arr == null || !arr.isArray()) {
            return;
        }
        ArrayNode copied = objectMapper.createArrayNode();
        for (int i = 0; i < arr.size() && i < maxCount; i++) {
            copied.add(arr.get(i));
        }
        target.set(key, copied);
    }

    private String trimSnippet(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isBlank()) {
            return null;
        }
        if (value.length() <= MAX_SNIPPET_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SNIPPET_LENGTH);
    }

    private String shortenText(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isBlank()) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private String buildEvidenceDedupeKey(String filePath, Integer startLine, Integer endLine, String snippet) {
        return safeText(filePath)
                + "|"
                + (startLine == null ? "?" : startLine)
                + "|"
                + (endLine == null ? "?" : endLine)
                + "|"
                + safeText(snippet);
    }

    public record LlmContextBundle(
            Map<String, Object> structureEngineOutput,
            List<LlmRequest.EvidenceSnippet> evidenceBundle
    ) {
    }
}
