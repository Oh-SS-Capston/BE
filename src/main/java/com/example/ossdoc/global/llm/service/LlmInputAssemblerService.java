package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.example.ossdoc.global.llm.model.CoreMethodSeed;
import com.example.ossdoc.global.llm.model.CoreTypeSeed;
import com.example.ossdoc.global.llm.model.LlmContextBundle;
import com.example.ossdoc.global.llm.model.SourceAnchor;
import com.example.ossdoc.global.llm.service.support.LlmInputAssemblerBuildSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * LLM 입력 조립 서비스.
 * 서비스는 아티팩트 조회와 단계 오케스트레이션만 담당하고,
 * 시드 생성/근거 가공 세부 구현은 지원 컴포넌트로 위임한다.
 */
@Service
@RequiredArgsConstructor
public class LlmInputAssemblerService {

    private static final int MAX_AUTO_EVIDENCE = 60;

    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final LlmInputAssemblerBuildSupport llmInputAssemblerBuildSupport;

    /**
     * LLM 실행에 필요한 구조 시드와 근거 번들을 만든다.
     */
    @Transactional(readOnly = true)
    public LlmContextBundle assemble(LlmRequest request) {
        if (!request.useAutoAssemble()) {
            return fromManualRequest(request);
        }
        try {
            ObjectNode structure = buildAutoStructure(request.getRunId(), request.useKorean());
            List<LlmRequest.EvidenceSnippet> evidenceBundle = llmInputAssemblerBuildSupport.resolveEvidenceBundle(
                    request,
                    structure.path("cautionSeed")
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
     * 수동 입력 모드에서는 요청 JSON을 그대로 사용한다.
     */
    private LlmContextBundle fromManualRequest(LlmRequest request) {
        if (request.getStructureEngineOutput() == null || request.getStructureEngineOutput().isEmpty()) {
            throw new LlmException(LlmErrorCode.CONTEXT_ASSEMBLE_FAILED);
        }
        List<LlmRequest.EvidenceSnippet> evidence = request.getEvidenceBundle() == null
                ? List.of()
                : request.getEvidenceBundle();
        return new LlmContextBundle(
                request.getStructureEngineOutput(),
                llmInputAssemblerBuildSupport.trimEvidenceSnippets(evidence, MAX_AUTO_EVIDENCE)
        );
    }

    /**
     * 자동 조합: 아티팩트를 읽고 구조 시드를 생성한다.
     */
    private ObjectNode buildAutoStructure(String runId, boolean forceKorean) {
        JsonNode apiMap = loadOptionalArtifactMeta(runId, ArtifactKind.API_MAP_JSON);
        JsonNode apiSurface = loadOptionalArtifactMeta(runId, ArtifactKind.API_SURFACE_JSON);
        JsonNode symbolSourceIndex = loadOptionalArtifactMeta(runId, ArtifactKind.SYMBOL_SOURCE_INDEX_JSON);
        JsonNode ruleCandidates = requireArtifactMeta(runId, ArtifactKind.RULE_CANDIDATES_JSON);
        JsonNode rankings = requireArtifactMeta(runId, ArtifactKind.RANKINGS_JSON);
        JsonNode subsystems = requireArtifactMeta(runId, ArtifactKind.SUBSYSTEMS_JSON);

        Map<String, String> publicApiTypeBySymbolId = llmInputAssemblerBuildSupport.indexPublicApiTypeBySymbolId(apiMap, apiSurface);
        Map<String, SourceAnchor> sourceAnchorBySymbolId = llmInputAssemblerBuildSupport.indexSourceAnchorBySymbolId(symbolSourceIndex);
        Map<String, SourceAnchor> sourceAnchorByFqn = llmInputAssemblerBuildSupport.indexSourceAnchorByFqn(symbolSourceIndex);

        List<CoreTypeSeed> coreTypes = llmInputAssemblerBuildSupport.extractCoreTypes(
                apiMap,
                rankings,
                subsystems,
                publicApiTypeBySymbolId
        );
        coreTypes = llmInputAssemblerBuildSupport.applyTypeSourceAnchors(coreTypes, sourceAnchorBySymbolId, sourceAnchorByFqn);

        List<CoreMethodSeed> coreMethods = llmInputAssemblerBuildSupport.extractCoreMethods(
                apiMap,
                rankings,
                coreTypes,
                ruleCandidates,
                publicApiTypeBySymbolId
        );
        coreMethods = llmInputAssemblerBuildSupport.applyMethodSourceAnchors(coreMethods, sourceAnchorBySymbolId, sourceAnchorByFqn);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("runId", runId);
        root.put("generatedAt", OffsetDateTime.now().toString());
        root.put("language", forceKorean ? "ko-KR" : "en-US");

        root.set("overviewSeed", llmInputAssemblerBuildSupport.buildOverviewSeed(runId, apiMap, rankings, subsystems));
        root.set("cautionSeed", llmInputAssemblerBuildSupport.buildCautionSeed(ruleCandidates, coreTypes, coreMethods));
        root.set("coreClassSeed", llmInputAssemblerBuildSupport.buildCoreClassSeed(coreTypes));
        root.set("coreMethodSeed", llmInputAssemblerBuildSupport.buildCoreMethodSeed(coreMethods));
        root.set("methodFlowSeed", llmInputAssemblerBuildSupport.buildMethodFlowSeed(apiMap, coreMethods));
        root.set("extensionSeed", llmInputAssemblerBuildSupport.buildExtensionSeed(apiMap, coreTypes, subsystems));
        root.set("directories", llmInputAssemblerBuildSupport.buildDirectories(apiMap, coreTypes, coreMethods));
        root.set("evidenceIndex", llmInputAssemblerBuildSupport.buildEvidenceIndex(coreTypes, coreMethods, ruleCandidates));
        root.set("qualityGate", llmInputAssemblerBuildSupport.buildQualityGate(apiMap, ruleCandidates, rankings, subsystems, symbolSourceIndex));

        // 하위 호환용 중계 필드
        ObjectNode publicSurface = root.putObject("publicSurface");
        publicSurface.set("coreClasses", llmInputAssemblerBuildSupport.buildCoreClassSeed(coreTypes));
        publicSurface.set("coreMethods", llmInputAssemblerBuildSupport.buildCoreMethodSeed(coreMethods));
        publicSurface.set("extensionPoints", llmInputAssemblerBuildSupport.buildExtensionSeed(apiMap, coreTypes, subsystems));
        publicSurface.set("directories", llmInputAssemblerBuildSupport.buildDirectories(apiMap, coreTypes, coreMethods));
        publicSurface.set("apiEntries", llmInputAssemblerBuildSupport.buildApiEntries(coreMethods));

        return root;
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
                .orElse(NullNode.getInstance());
    }
}
