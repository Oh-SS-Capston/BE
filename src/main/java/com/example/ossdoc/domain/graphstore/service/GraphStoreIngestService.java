package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.converter.FactsEdgeConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsEvidenceConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsSymbolConverter;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.dto.request.GraphStoreIngestRequest;
import com.example.ossdoc.domain.graphstore.dto.response.GraphStoreIngestResponse;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidenceId;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.Observation;
import com.example.ossdoc.domain.graphstore.entity.ObservationEvidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.entity.SymbolEvidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEvidenceId;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.exception.GraphStoreException;
import com.example.ossdoc.domain.graphstore.exception.code.GraphStoreErrorCode;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.model.projection.EdgeEvidenceLinkKeyRow;
import com.example.ossdoc.domain.graphstore.model.projection.EvidenceLookupRow;
import com.example.ossdoc.domain.graphstore.model.projection.SymbolEvidenceLinkKeyRow;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityIssue;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowIssue;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowReport;
import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowIntegratedReport;
import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowIntegratedReportFactory;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateDecision;
import com.example.ossdoc.domain.graphstore.service.promotion.BeanConfigurationShadowCandidateGenerator;
import com.example.ossdoc.domain.graphstore.service.promotion.BeanConfigurationShadowParityAnalyzer;
import com.example.ossdoc.domain.graphstore.service.promotion.DiShadowCandidateGenerator;
import com.example.ossdoc.domain.graphstore.service.promotion.DiShadowParityAnalyzer;
import com.example.ossdoc.domain.graphstore.service.promotion.EndpointEventSpiShadowCandidateGenerator;
import com.example.ossdoc.domain.graphstore.service.promotion.EndpointEventSpiShadowParityAnalyzer;
import com.example.ossdoc.domain.graphstore.service.promotion.ObservationPromotionShadowAnalyzer;
import com.example.ossdoc.domain.graphstore.service.promotion.ReflectionShadowCandidateGenerator;
import com.example.ossdoc.domain.graphstore.service.promotion.ReflectionShadowParityAnalyzer;
import com.example.ossdoc.domain.graphstore.service.promotion.ShadowFactsIndex;
import com.example.ossdoc.domain.graphstore.service.promotion.gate.SemanticPromotionGateProperties;
import com.example.ossdoc.domain.graphstore.service.promotion.gate.SemanticPromotionResponsibilityGate;
import com.example.ossdoc.domain.graphstore.repository.EdgeEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.EvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.ObservationEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.ObservationRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.module.entity.ModuleEntity;
import com.example.ossdoc.domain.module.repository.FileIndexRepository;
import com.example.ossdoc.domain.module.repository.ModuleRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GraphStoreIngestService {

    private static final int BATCH_SIZE = 500;

    private final RepoRunRepository repoRunRepository;
    private final ArtifactRepository artifactRepository;

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final EvidenceRepository evidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final ObservationRepository observationRepository;
    private final ObservationEvidenceRepository observationEvidenceRepository;
    private final SymbolEvidenceRepository symbolEvidenceRepository;
    private final FileIndexRepository fileIndexRepository;
    private final ModuleRepository moduleRepository;

    private final FactsEvidenceConverter factsEvidenceConverter;
    private final FactsSymbolConverter factsSymbolConverter;
    private final FactsEdgeConverter factsEdgeConverter;
    private final SymbolIdGenerator symbolIdGenerator;
    private final GraphStoreFactsNormalizer graphStoreFactsNormalizer;

    private final SemanticPromotionResponsibilityGate
            semanticPromotionResponsibilityGate;

    private final ObjectMapper objectMapper;

    /**
     * Spring이 사용하는 전체 의존성 생성자.
     */
    @Autowired
    public GraphStoreIngestService(
            RepoRunRepository repoRunRepository,
            ArtifactRepository artifactRepository,
            SymbolRepository symbolRepository,
            EdgeRepository edgeRepository,
            EvidenceRepository evidenceRepository,
            EdgeEvidenceRepository edgeEvidenceRepository,
            ObservationRepository observationRepository,
            ObservationEvidenceRepository observationEvidenceRepository,
            SymbolEvidenceRepository symbolEvidenceRepository,
            FileIndexRepository fileIndexRepository,
            ModuleRepository moduleRepository,
            FactsEvidenceConverter factsEvidenceConverter,
            FactsSymbolConverter factsSymbolConverter,
            FactsEdgeConverter factsEdgeConverter,
            SymbolIdGenerator symbolIdGenerator,
            GraphStoreFactsNormalizer graphStoreFactsNormalizer,
            SemanticPromotionResponsibilityGate
                    semanticPromotionResponsibilityGate,
            ObjectMapper objectMapper
    ) {
        this.repoRunRepository = repoRunRepository;
        this.artifactRepository = artifactRepository;
        this.symbolRepository = symbolRepository;
        this.edgeRepository = edgeRepository;
        this.evidenceRepository = evidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
        this.observationRepository = observationRepository;
        this.observationEvidenceRepository =
                observationEvidenceRepository;
        this.symbolEvidenceRepository =
                symbolEvidenceRepository;
        this.fileIndexRepository = fileIndexRepository;
        this.moduleRepository = moduleRepository;
        this.factsEvidenceConverter = factsEvidenceConverter;
        this.factsSymbolConverter = factsSymbolConverter;
        this.factsEdgeConverter = factsEdgeConverter;
        this.symbolIdGenerator = symbolIdGenerator;
        this.graphStoreFactsNormalizer =
                graphStoreFactsNormalizer;
        this.semanticPromotionResponsibilityGate =
                semanticPromotionResponsibilityGate;
        this.objectMapper = objectMapper;
    }

    /**
     * 10-3-4A 이전 테스트와 수동 생성 코드의 source compatibility를
     * 유지하기 위한 생성자.
     *
     * Spring Bean 생성에는 위의 @Autowired 생성자가 사용된다.
     */
    public GraphStoreIngestService(
            RepoRunRepository repoRunRepository,
            ArtifactRepository artifactRepository,
            SymbolRepository symbolRepository,
            EdgeRepository edgeRepository,
            EvidenceRepository evidenceRepository,
            EdgeEvidenceRepository edgeEvidenceRepository,
            ObservationRepository observationRepository,
            ObservationEvidenceRepository observationEvidenceRepository,
            SymbolEvidenceRepository symbolEvidenceRepository,
            FileIndexRepository fileIndexRepository,
            ModuleRepository moduleRepository,
            FactsEvidenceConverter factsEvidenceConverter,
            FactsSymbolConverter factsSymbolConverter,
            FactsEdgeConverter factsEdgeConverter,
            SymbolIdGenerator symbolIdGenerator,
            GraphStoreFactsNormalizer graphStoreFactsNormalizer,
            ObjectMapper objectMapper
    ) {
        this(
                repoRunRepository,
                artifactRepository,
                symbolRepository,
                edgeRepository,
                evidenceRepository,
                edgeEvidenceRepository,
                observationRepository,
                observationEvidenceRepository,
                symbolEvidenceRepository,
                fileIndexRepository,
                moduleRepository,
                factsEvidenceConverter,
                factsSymbolConverter,
                factsEdgeConverter,
                symbolIdGenerator,
                graphStoreFactsNormalizer,
                defaultSemanticPromotionResponsibilityGate(),
                objectMapper
        );
    }

    private static SemanticPromotionResponsibilityGate
    defaultSemanticPromotionResponsibilityGate() {
        SemanticPromotionGateProperties properties =
                new SemanticPromotionGateProperties();

        /*
         * 단위 테스트에서 이전 생성자를 사용할 때 Gate가 테스트 대상의
         * 저장·검증 동작에 영향을 주지 않도록 평가를 비활성화한다.
         */
        properties.setEnabled(false);

        return new SemanticPromotionResponsibilityGate(
                properties
        );
    }

    @Transactional
    public GraphStoreIngestResponse ingest(GraphStoreIngestRequest request, Long userId) {
        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new GraphStoreException(GraphStoreErrorCode.RUN_NOT_FOUND));

        if (run.getOwner() == null || !run.getOwner().getId().equals(userId)) {
            throw new GraphStoreException(GraphStoreErrorCode.RUN_ACCESS_DENIED);
        }

        // C-1: module 테이블 적재 / C-2: symbol.module_id 연결에 사용
        Map<String, ModuleEntity> moduleCache = persistModules(run);

        Artifact factsArtifact = resolveFactsArtifact(request);

        JsonNode root = factsArtifact.getMeta();
        if (root == null || root.isNull()) {
            throw new GraphStoreException(GraphStoreErrorCode.FACTS_READ_FAILED);
        }

        RawFactsDocumentDto rawFacts;
        try {
            rawFacts = objectMapper.treeToValue(root, RawFactsDocumentDto.class);
        } catch (Exception e) {
            log.error("[GRAPHSTORE] facts.json deserialization failed. runId={}", run.getRunId(), e);
            throw new GraphStoreException(GraphStoreErrorCode.INVALID_FACTS_SCHEMA);
        }

        NormalizedFactsDocument facts = graphStoreFactsNormalizer.normalize(rawFacts);
        validateFacts(facts);
        // shadow generator들이 symbols/observations 인덱스를 각자 다시 만들지 않도록 ingest 단위에서 한 번만 구성한다.
        ShadowFactsIndex shadowFactsIndex = ShadowFactsIndex.from(facts);

        ObservationPromotionShadowReport shadowReport =
                ObservationPromotionShadowAnalyzer.analyze(facts);

        logObservationPromotionShadow(
                run.getRunId(),
                shadowReport
        );

        ObservationPromotionCandidateGenerationResult
                candidateGeneration =
                EndpointEventSpiShadowCandidateGenerator
                        .generate(
                                facts,
                                objectMapper
                        );

        ObservationPromotionCandidateParityReport
                candidateParity =
                EndpointEventSpiShadowParityAnalyzer
                        .compare(
                                shadowFactsIndex,
                                candidateGeneration,
                                objectMapper
                        );

        logEndpointEventSpiShadowParity(
                run.getRunId(),
                candidateGeneration,
                candidateParity
        );

        ObservationPromotionCandidateGenerationResult
                beanConfigurationGeneration =
                BeanConfigurationShadowCandidateGenerator
                        .generate(
                                facts,
                                objectMapper
                        );

        ObservationPromotionCandidateParityReport
                beanConfigurationParity =
                BeanConfigurationShadowParityAnalyzer
                        .compare(
                                shadowFactsIndex,
                                beanConfigurationGeneration,
                                objectMapper
                        );

        logBeanConfigurationShadowParity(
                run.getRunId(),
                beanConfigurationGeneration,
                beanConfigurationParity
        );

        ObservationPromotionCandidateGenerationResult
                reflectionGeneration =
                ReflectionShadowCandidateGenerator
                        .generate(
                                shadowFactsIndex,
                                objectMapper
                        );

        ObservationPromotionCandidateParityReport
                reflectionParity =
                ReflectionShadowParityAnalyzer
                        .compare(
                                shadowFactsIndex,
                                reflectionGeneration,
                                objectMapper
                        );

        logReflectionShadowParity(
                run.getRunId(),
                reflectionGeneration,
                reflectionParity
        );

        ObservationPromotionCandidateGenerationResult
                diGeneration =
                DiShadowCandidateGenerator
                        .generate(
                                shadowFactsIndex,
                                objectMapper
                        );

        ObservationPromotionCandidateParityReport
                diParity =
                DiShadowParityAnalyzer
                        .compare(
                                shadowFactsIndex,
                                diGeneration,
                                objectMapper
                        );

        logDiShadowParity(
                run.getRunId(),
                diGeneration,
                diParity
        );

        SemanticPromotionShadowIntegratedReport
                integratedShadowReport =
                SemanticPromotionShadowIntegratedReportFactory
                        .create(
                                shadowReport,
                                candidateGeneration,
                                candidateParity,
                                beanConfigurationGeneration,
                                beanConfigurationParity,
                                reflectionGeneration,
                                reflectionParity,
                                diGeneration,
                                diParity
                        );

        logIntegratedSemanticShadowReport(
                run.getRunId(),
                integratedShadowReport
        );

        SemanticPromotionGateDecision gateDecision =
                semanticPromotionResponsibilityGate
                        .evaluate(
                                run.getRunId(),
                                run.getRepoUrl(),
                                integratedShadowReport
                        );

        logSemanticPromotionGateDecision(
                gateDecision
        );

        EvidenceSaveResult evidenceSaveResult = saveEvidence(run, facts);
        SymbolSaveResult symbolSaveResult = saveSymbols(run, facts, evidenceSaveResult.evidenceMap(), moduleCache);
        EdgeSaveResult edgeSaveResult = saveEdges(run, facts, symbolSaveResult.symbolMap(), evidenceSaveResult.evidenceMap());
        int observationsSaved = saveObservations(
                run,
                facts,
                evidenceSaveResult.evidenceMap()
        );

        return GraphStoreIngestResponse.builder()
                .runId(run.getRunId())
                .artifactId(factsArtifact.getArtifactId())
                .evidencesSaved(evidenceSaveResult.savedCount())
                .symbolsSaved(symbolSaveResult.savedCount())
                .edgesSaved(edgeSaveResult.edgesSaved())
                .edgeEvidenceSaved(edgeSaveResult.edgeEvidenceSaved())
                .skippedRelations(edgeSaveResult.skippedRelations())
                .symbolEvidenceSaved(symbolSaveResult.symbolEvidenceSaved())
                .observationsDetected(facts.observations().size())
                .observationsSaved(observationsSaved)
                .build();
    }

    /**
     * BUILD_MANIFEST artifact를 읽어 module 테이블에 적재하고,
     * gradle projectPath → ModuleEntity 맵을 반환한다.
     *
     * - artifact 부재 또는 역직렬화 실패 시 warn 로그 + 빈 맵 반환 (graceful degrade)
     * - PK: runId + "::" + gradleProjectPath (run 간 충돌 방지)
     * - sourceRoots/testRoots/resourceRoots가 null이면 빈 배열로 채움 (NOT NULL JSONB 제약)
     * - 재실행 시 중복 INSERT 방지: 기존 적재분은 건너뜀
     */
    private Map<String, ModuleEntity> persistModules(RepoRun run) {
        Optional<Artifact> opt = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(run.getRunId(), ArtifactKind.BUILD_MANIFEST);
        if (opt.isEmpty()) {
            log.warn("[GRAPHSTORE] BUILD_MANIFEST not found, skipping module persist. runId={}", run.getRunId());
            return Map.of();
        }

        BuildManifest manifest;
        try {
            manifest = objectMapper.treeToValue(opt.get().getMeta(), BuildManifest.class);
        } catch (Exception e) {
            log.warn("[GRAPHSTORE] BUILD_MANIFEST deserialization failed, skipping. runId={}", run.getRunId(), e);
            return Map.of();
        }

        // 재실행 시 기존 적재분을 건너뛰기 위해 미리 로드
        Map<String, ModuleEntity> existing = moduleRepository.findAllByRun_RunId(run.getRunId())
                .stream()
                .collect(Collectors.toMap(ModuleEntity::getModuleId, m -> m));

        String buildType = manifest.getBuildTool() != null ? manifest.getBuildTool().name() : null;
        Map<String, ModuleEntity> result = new LinkedHashMap<>();
        List<ModuleEntity> toSave = new ArrayList<>();

        for (BuildModuleManifest dto : manifest.getModules()) {
            if (dto.getModuleId() == null) continue;

            String pk = run.getRunId() + "::" + dto.getModuleId();
            ModuleEntity entity = existing.get(pk);

            if (entity == null) {
                String name = dto.getName() != null && !dto.getName().isBlank()
                        ? dto.getName() : dto.getModuleId();
                entity = new ModuleEntity(
                        pk, run, name, buildType,
                        null, null, // packaging, javaVersion
                        dto.getGroupId(), dto.getArtifactId(), dto.getVersion(),
                        toJsonArray(dto.getSourceRoots()),
                        toJsonArray(dto.getTestRoots()),
                        toJsonArray(dto.getResourceRoots())
                );
                toSave.add(entity);
            }
            result.put(dto.getModuleId(), entity);
        }

        if (!toSave.isEmpty()) {
            moduleRepository.saveAll(toSave);
        }
        log.info("[GRAPHSTORE] modules persisted: saved={}, total={}, runId={}",
                toSave.size(), result.size(), run.getRunId());
        return result;
    }

    private JsonNode toJsonArray(List<String> items) {
        var arr = JsonNodeFactory.instance.arrayNode();
        if (items != null) {
            items.forEach(arr::add);
        }
        return arr;
    }

    private Artifact resolveFactsArtifact(GraphStoreIngestRequest request) {
        if (request.getArtifactId() != null) {
            return artifactRepository.findById(request.getArtifactId())
                    .orElseThrow(() -> new GraphStoreException(GraphStoreErrorCode.FACTS_ARTIFACT_NOT_FOUND));
        }

        return artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(request.getRunId(), ArtifactKind.FACTS_JSON)
                .orElseThrow(() -> new GraphStoreException(GraphStoreErrorCode.FACTS_ARTIFACT_NOT_FOUND));
    }

    private void validateFacts(NormalizedFactsDocument facts) {
        if (facts == null || facts.symbols() == null || facts.relations() == null) {
            throw new GraphStoreException(GraphStoreErrorCode.INVALID_FACTS_SCHEMA);
        }
        if (facts.schemaVersion() == null) {
            log.warn("[GRAPHSTORE] facts artifact has no schema_version field.");
        }
    }

    /**
     * Observation → semantic relation 승격 계약을 shadow mode로 검증한다.
     *
     * 이 단계에서는 Edge를 추가·변경하지 않고 비교 결과만 기록한다.
     */
    private void logObservationPromotionShadow(
            String runId,
            ObservationPromotionShadowReport report
    ) {
        if (report == null || report.totalObservations() == 0) {
            log.debug(
                    "[GRAPHSTORE-SHADOW] no observations available. runId={}",
                    runId
            );
            return;
        }

        if (report.promotableObservations() == 0) {
            log.debug(
                    "[GRAPHSTORE-SHADOW] no promotable observations. "
                            + "runId={}, total={}, counts={}",
                    runId,
                    report.totalObservations(),
                    report.counts()
            );
            return;
        }

        if (!report.hasMismatches()) {
            log.info(
                    "[GRAPHSTORE-SHADOW] promotion contract matched. "
                            + "runId={}, promotable={}, matched={}, counts={}",
                    runId,
                    report.promotableObservations(),
                    report.matchedCount(),
                    report.counts()
            );
            return;
        }

        List<String> samples = report.issues()
                .stream()
                .filter(ObservationPromotionShadowIssue::isMismatch)
                .limit(10)
                .map(ObservationPromotionShadowIssue::summary)
                .toList();

        log.warn(
                "[GRAPHSTORE-SHADOW] promotion contract mismatch. "
                        + "runId={}, promotable={}, matched={}, "
                        + "mismatches={}, counts={}, samples={}",
                runId,
                report.promotableObservations(),
                report.matchedCount(),
                report.mismatchCount(),
                report.counts(),
                samples
        );
    }


    /**
     * Endpoint·Event·SPI shadow 후보의 exact parity 결과를 기록한다.
     *
     * 이 단계에서도 Relation/Edge 저장 내용은 변경하지 않는다.
     */
    private void logEndpointEventSpiShadowParity(
            String runId,
            ObservationPromotionCandidateGenerationResult generation,
            ObservationPromotionCandidateParityReport report
    ) {
        if (generation == null
                || generation.eligibleObservationCount() == 0) {
            log.debug(
                    "[GRAPHSTORE-SHADOW-CANDIDATE] "
                            + "no endpoint/event/spi observations. runId={}",
                    runId
            );
            return;
        }

        if (report == null) {
            log.warn(
                    "[GRAPHSTORE-SHADOW-CANDIDATE] "
                            + "parity report is missing. runId={}",
                    runId
            );
            return;
        }

        if (!report.hasMismatches()
                && generation.warnings().isEmpty()) {
            log.info(
                    "[GRAPHSTORE-SHADOW-CANDIDATE] exact parity matched. "
                            + "runId={}, eligibleObservations={}, "
                            + "generated={}, extraction={}, counts={}",
                    runId,
                    generation.eligibleObservationCount(),
                    report.generatedCandidateCount(),
                    report.extractionRelationCount(),
                    report.counts()
            );
            return;
        }

        List<String> samples =
                report.issues().stream()
                        .filter(
                                ObservationPromotionCandidateParityIssue
                                        ::isMismatch
                        )
                        .limit(10)
                        .map(
                                ObservationPromotionCandidateParityIssue
                                        ::summary
                        )
                        .toList();

        log.warn(
                "[GRAPHSTORE-SHADOW-CANDIDATE] exact parity mismatch. "
                        + "runId={}, eligibleObservations={}, "
                        + "generated={}, extraction={}, mismatches={}, "
                        + "counts={}, generationWarnings={}, samples={}",
                runId,
                generation.eligibleObservationCount(),
                report.generatedCandidateCount(),
                report.extractionRelationCount(),
                report.mismatchCount(),
                report.counts(),
                generation.warnings(),
                samples
        );
    }


/**
 * Bean·Configuration shadow 후보의 exact parity 결과를 기록한다.
 *
 * Relation/Edge 저장 결과는 기존 Extraction Relation 기준으로 유지한다.
 */
private void logBeanConfigurationShadowParity(
        String runId,
        ObservationPromotionCandidateGenerationResult generation,
        ObservationPromotionCandidateParityReport report
) {
    if (generation == null
            || generation.eligibleObservationCount() == 0) {
        log.debug(
                "[GRAPHSTORE-SHADOW-BEAN-CONFIG] "
                        + "no bean/configuration observations. runId={}",
                runId
        );
        return;
    }

    if (report == null) {
        log.warn(
                "[GRAPHSTORE-SHADOW-BEAN-CONFIG] "
                        + "parity report is missing. runId={}",
                runId
        );
        return;
    }

    if (!report.hasMismatches()
            && generation.warnings().isEmpty()) {
        log.info(
                "[GRAPHSTORE-SHADOW-BEAN-CONFIG] exact parity matched. "
                        + "runId={}, eligibleObservations={}, "
                        + "generated={}, extraction={}, counts={}",
                runId,
                generation.eligibleObservationCount(),
                report.generatedCandidateCount(),
                report.extractionRelationCount(),
                report.counts()
        );
        return;
    }

    List<String> samples =
            report.issues().stream()
                    .filter(
                            ObservationPromotionCandidateParityIssue
                                    ::isMismatch
                    )
                    .limit(10)
                    .map(
                            ObservationPromotionCandidateParityIssue
                                    ::summary
                    )
                    .toList();

    log.warn(
            "[GRAPHSTORE-SHADOW-BEAN-CONFIG] exact parity mismatch. "
                    + "runId={}, eligibleObservations={}, "
                    + "generated={}, extraction={}, mismatches={}, "
                    + "counts={}, generationWarnings={}, samples={}",
            runId,
            generation.eligibleObservationCount(),
            report.generatedCandidateCount(),
            report.extractionRelationCount(),
            report.mismatchCount(),
            report.counts(),
            generation.warnings(),
            samples
    );
}


/**
 * Reflection shadow 후보의 exact parity 결과를 기록한다.
 *
 * Relation/Edge 저장 결과는 기존 Extraction Relation 기준으로 유지한다.
 */
private void logReflectionShadowParity(
        String runId,
        ObservationPromotionCandidateGenerationResult generation,
        ObservationPromotionCandidateParityReport report
) {
    if (generation == null
            || generation.eligibleObservationCount() == 0) {
        log.debug(
                "[GRAPHSTORE-SHADOW-REFLECTION] "
                        + "no reflection observations. runId={}",
                runId
        );
        return;
    }

    if (report == null) {
        log.warn(
                "[GRAPHSTORE-SHADOW-REFLECTION] "
                        + "parity report is missing. runId={}",
                runId
        );
        return;
    }

    if (!report.hasMismatches()
            && generation.warnings().isEmpty()) {
        log.info(
                "[GRAPHSTORE-SHADOW-REFLECTION] exact parity matched. "
                        + "runId={}, eligibleObservations={}, "
                        + "generated={}, extraction={}, counts={}",
                runId,
                generation.eligibleObservationCount(),
                report.generatedCandidateCount(),
                report.extractionRelationCount(),
                report.counts()
        );
        return;
    }

    List<String> samples =
            report.issues().stream()
                    .filter(
                            ObservationPromotionCandidateParityIssue
                                    ::isMismatch
                    )
                    .limit(10)
                    .map(
                            ObservationPromotionCandidateParityIssue
                                    ::summary
                    )
                    .toList();

    log.warn(
            "[GRAPHSTORE-SHADOW-REFLECTION] exact parity mismatch. "
                    + "runId={}, eligibleObservations={}, "
                    + "generated={}, extraction={}, mismatches={}, "
                    + "counts={}, generationWarnings={}, samples={}",
            runId,
            generation.eligibleObservationCount(),
            report.generatedCandidateCount(),
            report.extractionRelationCount(),
            report.mismatchCount(),
            report.counts(),
            generation.warnings(),
            samples
    );
}


/**
 * DI shadow 후보의 exact parity 결과를 기록한다.
 *
 * Relation/Edge 저장 결과는 기존 Extraction INJECTS Relation 기준으로 유지한다.
 */
private void logDiShadowParity(
        String runId,
        ObservationPromotionCandidateGenerationResult generation,
        ObservationPromotionCandidateParityReport report
) {
    if (generation == null
            || generation.eligibleObservationCount() == 0) {
        log.debug(
                "[GRAPHSTORE-SHADOW-DI] "
                        + "no DI injection observations. runId={}",
                runId
        );
        return;
    }

    if (report == null) {
        log.warn(
                "[GRAPHSTORE-SHADOW-DI] "
                        + "parity report is missing. runId={}",
                runId
        );
        return;
    }

    if (!report.hasMismatches()
            && generation.warnings().isEmpty()) {
        log.info(
                "[GRAPHSTORE-SHADOW-DI] exact parity matched. "
                        + "runId={}, eligibleObservations={}, "
                        + "generated={}, extraction={}, counts={}",
                runId,
                generation.eligibleObservationCount(),
                report.generatedCandidateCount(),
                report.extractionRelationCount(),
                report.counts()
        );
        return;
    }

    List<String> samples =
            report.issues().stream()
                    .filter(
                            ObservationPromotionCandidateParityIssue
                                    ::isMismatch
                    )
                    .limit(10)
                    .map(
                            ObservationPromotionCandidateParityIssue
                                    ::summary
                    )
                    .toList();

    log.warn(
            "[GRAPHSTORE-SHADOW-DI] exact parity mismatch. "
                    + "runId={}, eligibleObservations={}, "
                    + "generated={}, extraction={}, mismatches={}, "
                    + "counts={}, generationWarnings={}, samples={}",
            runId,
            generation.eligibleObservationCount(),
            report.generatedCandidateCount(),
            report.extractionRelationCount(),
            report.mismatchCount(),
            report.counts(),
            generation.warnings(),
            samples
    );
}


/**
 * 전체 의미 Relation shadow 계약과 exact parity를 통합해서 기록한다.
 *
 * persistencePromotionReady는 저장 책임 전환의 자동 실행 조건이 아니라,
 * 다음 단계에서 판단할 수 있도록 제공하는 검증 지표다.
 */
private void logIntegratedSemanticShadowReport(
        String runId,
        SemanticPromotionShadowIntegratedReport report
) {
    if (report == null) {
        log.warn(
                "[GRAPHSTORE-SHADOW-INTEGRATED] "
                        + "integrated report is missing. runId={}",
                runId
        );
        return;
    }

    if (report.totalObservations() == 0
            && report.eligibleObservationCount() == 0) {
        log.debug(
                "[GRAPHSTORE-SHADOW-INTEGRATED] "
                        + "no observations available. runId={}",
                runId
        );
        return;
    }

    if (report.isPersistencePromotionReady()) {
        log.info(
                "[GRAPHSTORE-SHADOW-INTEGRATED] "
                        + "semantic promotion shadow verified. "
                        + "runId={}, totalObservations={}, "
                        + "promotableObservations={}, eligible={}, "
                        + "generated={}, extraction={}, matched={}, "
                        + "contractCounts={}, candidateCounts={}, "
                        + "persistencePromotionReady=true, stages={}",
                runId,
                report.totalObservations(),
                report.promotableObservations(),
                report.eligibleObservationCount(),
                report.generatedCandidateCount(),
                report.extractionRelationCount(),
                report.exactMatchedCount(),
                report.contractCounts(),
                report.candidateCounts(),
                report.stageSummaries()
        );
        return;
    }

    log.warn(
            "[GRAPHSTORE-SHADOW-INTEGRATED] "
                    + "semantic promotion shadow not ready. "
                    + "runId={}, totalObservations={}, "
                    + "promotableObservations={}, eligible={}, "
                    + "generated={}, extraction={}, matched={}, "
                    + "contractMismatches={}, exactMismatches={}, "
                    + "generationWarnings={}, mismatchedStages={}, "
                    + "contractCounts={}, candidateCounts={}, "
                    + "persistencePromotionReady=false, "
                    + "mismatchSamples={}, warningSamples={}, stages={}",
            runId,
            report.totalObservations(),
            report.promotableObservations(),
            report.eligibleObservationCount(),
            report.generatedCandidateCount(),
            report.extractionRelationCount(),
            report.exactMatchedCount(),
            report.contractMismatchCount(),
            report.exactMismatchCount(),
            report.generationWarningCount(),
            report.mismatchedStageCount(),
            report.contractCounts(),
            report.candidateCounts(),
            report.mismatchSamples(20),
            report.warningSamples(20),
            report.stageSummaries()
    );
}


/**
 * 실제 OSS ingest 결과에 기반한 의미 Relation 책임 전환 Gate를 기록한다.
 *
 * candidatePersistencePermitted가 true여도 현재 단계에서는
 * 후보 Relation을 저장하지 않는다. 후속 writer 연결을 위한 판정값이다.
 */
private void logSemanticPromotionGateDecision(
        SemanticPromotionGateDecision decision
) {
    if (decision == null) {
        log.warn(
                "[GRAPHSTORE-SEMANTIC-GATE] "
                        + "gate decision is missing"
        );
        return;
    }

    switch (decision.status()) {
        case DISABLED,
             DUPLICATE_RUN_IGNORED ->
                log.debug(
                        "[GRAPHSTORE-SEMANTIC-GATE] {}",
                        decision.summary()
                );

        case BLOCKED ->
                log.warn(
                        "[GRAPHSTORE-SEMANTIC-GATE] "
                                + "validation window reset. {}",
                        decision.summary()
                );

        case WARMING_UP ->
                log.info(
                        "[GRAPHSTORE-SEMANTIC-GATE] "
                                + "validation window warming up. {}",
                        decision.summary()
                );

        case READY_BUT_PERSISTENCE_DISABLED ->
                log.info(
                        "[GRAPHSTORE-SEMANTIC-GATE] "
                                + "validation ready; candidate persistence "
                                + "remains disabled. {}",
                        decision.summary()
                );

        case READY ->
                log.warn(
                        "[GRAPHSTORE-SEMANTIC-GATE] "
                                + "candidate persistence is permitted by "
                                + "configuration, but no candidate writer "
                                + "is connected in this stage. {}",
                        decision.summary()
                );
    }
}

    /**
     * Evidence를 저장한다.
     * run 단위 선조회 인덱스를 사용해 건별 중복 조회(N+1)를 줄인다.
     */
    private EvidenceSaveResult saveEvidence(RepoRun run, NormalizedFactsDocument facts) {
        Map<String, Evidence> evidenceMap = new LinkedHashMap<>();
        int savedCount = 0;

        if (facts.evidence() == null || facts.evidence().isEmpty()) {
            return new EvidenceSaveResult(evidenceMap, savedCount);
        }

        Map<String, FileIndex> fileIndexCache = loadFileIndexCache(run);
        EvidenceLookupIndexes lookupIndexes = buildEvidenceLookupIndexes(run);
        ensureFileIndexes(
                run,
                facts.evidence().values().stream()
                        .map(NormalizedEvidenceFact::path)
                        .toList(),
                fileIndexCache
        );
        List<Evidence> newEvidence = new ArrayList<>();

        for (Map.Entry<String, NormalizedEvidenceFact> entry : facts.evidence().entrySet()) {
            String factEvidenceId = entry.getKey();
            NormalizedEvidenceFact dto = entry.getValue();

            FileIndex fileIndex = resolveFileIndex(run, dto.path(), fileIndexCache);
            Evidence candidate = factsEvidenceConverter.toEntity(run, dto, fileIndex);
            Evidence existing = findEvidenceInLookup(lookupIndexes, candidate);

            Evidence resolved;
            if (existing != null) {
                resolved = existing;
            } else {
                resolved = candidate;
                newEvidence.add(candidate);
                // 같은 facts 안에서 중복 evidence가 이어져도 추가 save 후보로 쌓이지 않도록
                // 저장 예정 엔티티를 먼저 lookup에 등록한다. 실제 DB 저장은 아래 batch에서 한 번에 처리한다.
                registerEvidenceLookup(lookupIndexes, resolved);
                savedCount++;
            }

            evidenceMap.put(factEvidenceId, resolved);
        }

        saveAllInBatches(newEvidence, BATCH_SIZE, evidenceRepository::saveAll);

        return new EvidenceSaveResult(evidenceMap, savedCount);
    }

    /**
     * run 범위 기존 evidence를 한 번에 읽어 중복 판별용 인덱스를 만든다.
     */
    private EvidenceLookupIndexes buildEvidenceLookupIndexes(
            RepoRun run
    ) {
        Map<String, Evidence> rawIdLookup =
                new HashMap<>();

        Map<EvidenceIdentitySupport.Signature, Evidence>
                signatureLookup = new HashMap<>();

        EvidenceLookupIndexes indexes =
                new EvidenceLookupIndexes(
                        rawIdLookup,
                        signatureLookup
                );

        List<EvidenceLookupRow> existing =
                evidenceRepository.findLookupRowsByRunId(
                        run.getRunId()
                );

        for (EvidenceLookupRow row : existing) {
            Evidence evidence = toLookupEvidence(
                    run,
                    row
            );
            registerEvidenceLookup(
                    indexes,
                    evidence
            );
        }

        return indexes;
    }

    /**
     * projection으로 조회한 기존 evidence row를 중복 판별용 경량 객체로 재구성한다.
     */
    private Evidence toLookupEvidence(
            RepoRun run,
            EvidenceLookupRow row
    ) {
        if (row == null) {
            return null;
        }

        FileIndex file = row.fileId() == null
                ? null
                : new FileIndex(
                        row.fileId(),
                        run,
                        null,
                        row.filePath(),
                        row.fileType() == null
                                ? detectFileType(row.filePath())
                                : row.fileType(),
                        null,
                        null
                );

        // 성능 최적화: 기존 evidence 중복 판별에는 Hibernate가 관리하는 전체 Entity가 필요하지 않다.
        // projection row에서 필요한 값만 담은 가벼운 Evidence를 재구성해 text/jsonb 필드와 연관 로딩 부담을 줄인다.
        return new Evidence(
                row.evidenceId(),
                run,
                row.evidenceType(),
                file,
                row.startLine(),
                row.startCol(),
                row.endLine(),
                row.endCol(),
                row.symbol(),
                row.snippet(),
                row.hash(),
                row.rawId(),
                row.attrs()
        );
    }

    /**
     * 신규 evidence 후보를 인덱스에서 조회해 기존 데이터와 중복 여부를 확인한다.
     */
    private Evidence findEvidenceInLookup(
            EvidenceLookupIndexes indexes,
            Evidence candidate
    ) {
        return EvidenceIdentitySupport.findExisting(
                indexes.rawIdLookup(),
                indexes.signatureLookup(),
                candidate
        );
    }

    /**
     * 저장 완료된 evidence를 중복 판별 인덱스에 등록한다.
     */
    private void registerEvidenceLookup(
            EvidenceLookupIndexes indexes,
            Evidence evidence
    ) {
        EvidenceIdentitySupport.register(
                indexes.rawIdLookup(),
                indexes.signatureLookup(),
                evidence
        );
    }

    /**
     * run 범위 file_index를 메모리 캐시로 적재한다.
     */
    private Map<String, FileIndex> loadFileIndexCache(RepoRun run) {
        Map<String, FileIndex> cache = new HashMap<>();
        List<FileIndex> existingFiles = fileIndexRepository.findAllByRun_RunId(run.getRunId());
        for (FileIndex fileIndex : existingFiles) {
            if (fileIndex.getPath() != null) {
                cache.put(fileIndex.getPath(), fileIndex);
            }
        }
        return cache;
    }

    /**
     * evidence/symbol에서 필요한 신규 file_index를 먼저 모아 batch 저장한다.
     * 이후 evidence signature와 source_file FK가 같은 캐시 객체를 참조해 개별 save를 피한다.
     */
    private void ensureFileIndexes(RepoRun run, List<String> rawPaths, Map<String, FileIndex> cache) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return;
        }

        Map<String, FileIndex> newFilesByPath = new LinkedHashMap<>();
        for (String rawPath : rawPaths) {
            String normalizedPath = normalizeEvidencePath(rawPath);
            if (normalizedPath == null
                    || cache.containsKey(normalizedPath)
                    || newFilesByPath.containsKey(normalizedPath)) {
                continue;
            }

            FileIndex created = new FileIndex(
                    null,
                    run,
                    null,
                    normalizedPath,
                    detectFileType(normalizedPath),
                    null,
                    null
            );
            newFilesByPath.put(normalizedPath, created);
            cache.put(normalizedPath, created);
        }

        // Evidence/Symbol 저장 전에 file_id를 확정해야 evidence signature와 source_file FK가 안정적으로 연결된다.
        saveAllInBatches(new ArrayList<>(newFilesByPath.values()), BATCH_SIZE, fileIndexRepository::saveAll);
    }

    /**
     * 경로 기준 file_index 조회/생성을 수행한다.
     * 캐시를 우선 사용해 반복 DB 조회를 줄인다.
     */
    private FileIndex resolveFileIndex(RepoRun run, String rawPath, Map<String, FileIndex> cache) {
        String normalizedPath = normalizeEvidencePath(rawPath);
        if (normalizedPath == null) {
            return null;
        }

        FileIndex existing = cache.get(normalizedPath);
        if (existing != null) {
            return existing;
        }

        FileIndex created = fileIndexRepository.save(new FileIndex(
                null,
                run,
                null,
                normalizedPath,
                detectFileType(normalizedPath),
                null,
                null
        ));
        cache.put(normalizedPath, created);
        return created;
    }

    private String normalizeEvidencePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replace('\\', '/');
    }

    private String detectFileType(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == path.length() - 1) {
            return "unknown";
        }
        return path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Symbol을 저장한다.
     * run 단위 선조회 캐시를 사용해 건별 symbol 조회 쿼리를 제거한다.
     */
    private SymbolSaveResult saveSymbols(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, Evidence> evidenceMap,
            Map<String, ModuleEntity> moduleCache  // C-2: gradle projectPath → ModuleEntity
    ) {
        Map<String, SymbolEntity> symbolMap = new LinkedHashMap<>();
        Map<String, SymbolEntity> existingSymbolsByQualifiedName = loadExistingSymbolsByQualifiedName(run);
        Map<String, FileIndex> sourceFileCache = loadFileIndexCache(run);
        ensureFileIndexes(
                run,
                facts.symbols().stream()
                        .map(NormalizedSymbolFact::sourceFile)
                        .toList(),
                sourceFileCache
        );
        List<SymbolEntity> newSymbols = new ArrayList<>();
        int savedCount = 0;
        int sourceFileLinkedCount = 0;
        int moduleLinkedCount = 0;

        for (NormalizedSymbolFact dto : facts.symbols()) {
            if (dto.symbol() == null || dto.symbol().isBlank()) {
                continue;
            }

            SymbolEntity symbol = existingSymbolsByQualifiedName.get(dto.symbol());
            if (symbol == null) {
                String symbolId = symbolIdGenerator.generate(run.getRunId(), dto.symbol());
                // FactsSymbolConverter:23 — module은 여전히 null, 연결은 아래에서 서비스가 담당
                symbol = factsSymbolConverter.toEntity(symbolId, run, dto);
                newSymbols.add(symbol);
                existingSymbolsByQualifiedName.put(dto.symbol(), symbol);
                savedCount++;
            }

            // C-2: dto.module()은 gradle projectPath (":core" 등), moduleCache는 C-1에서 적재됨
            if (dto.module() != null && symbol.getModule() == null) {
                ModuleEntity moduleEntity = moduleCache.get(dto.module());
                if (moduleEntity != null) {
                    symbol.assignModule(moduleEntity);
                    moduleLinkedCount++;
                }
            }

            FileIndex sourceFile = resolveSymbolSourceFile(run, dto, sourceFileCache);
            if (shouldAssignSourceFile(symbol, sourceFile)) {
                symbol.assignSourceFile(sourceFile);
                sourceFileLinkedCount++;
            }

            symbolMap.put(dto.symbol(), symbol);
        }

        // 신규 symbol은 한 번에 저장한다. owner/source span/link 계산은 아래에서 같은 엔티티 인스턴스를 이어서 사용한다.
        saveAllInBatches(newSymbols, BATCH_SIZE, symbolRepository::saveAll);

        Set<String> existingSymbolEvidenceKeys = loadExistingSymbolEvidenceKeys(run.getRunId());
        Set<String> newSymbolEvidenceKeys = new HashSet<>();
        List<PendingSymbolEvidence> pendingSymbolEvidence = new ArrayList<>();

        for (NormalizedSymbolFact dto : facts.symbols()) {
            SymbolEntity current = symbolMap.get(dto.symbol());
            if (current == null) {
                continue;
            }

            if (dto.ownerTypeSymbol() != null && !dto.ownerTypeSymbol().isBlank()) {
                SymbolEntity owner = symbolMap.get(dto.ownerTypeSymbol());
                if (owner != null && shouldAssignOwner(current, owner)) {
                    current.assignOwner(owner);
                }
            }

            if (dto.evidenceIds() != null && !dto.evidenceIds().isEmpty()) {
                Evidence sourceEvidence = selectSourceSpanEvidence(dto.evidenceIds(), evidenceMap);
                if (sourceEvidence != null && shouldAssignSourceSpan(current, sourceEvidence)) {
                    current.assignSourceSpan(sourceEvidence.getStartLine(), sourceEvidence.getEndLine());
                }

                // facts.json의 symbol.source_file은 병합 결과상 .class(bytecode)로 기록되지만,
                // 동일 심볼의 AST evidence는 실제 .java 경로를 보유한다.
                // AST evidence의 소스 파일을 .class보다 우선해 심볼 source file로 승격한다.
                FileIndex astSourceFile = selectSourceCodeFileIndex(dto.evidenceIds(), evidenceMap);
                if (astSourceFile != null && shouldAssignSourceFile(current, astSourceFile)) {
                    current.assignSourceFile(astSourceFile);
                    sourceFileLinkedCount++;
                }

                for (String factEvidenceId : dto.evidenceIds()) {
                    Evidence evidence = evidenceMap.get(factEvidenceId);
                    if (evidence == null || evidence.getEvidenceId() == null) {
                        continue;
                    }
                    String linkKey = current.getSymbolId() + ":" + evidence.getEvidenceId();
                    if (!existingSymbolEvidenceKeys.contains(linkKey) && newSymbolEvidenceKeys.add(linkKey)) {
                        pendingSymbolEvidence.add(new PendingSymbolEvidence(current, evidence));
                    }
                }
            }
        }

        List<SymbolEvidence> symbolEvidenceBuffer = new ArrayList<>(BATCH_SIZE);
        int symbolEvidenceSaved = 0;
        for (PendingSymbolEvidence pending : pendingSymbolEvidence) {
            SymbolEvidenceId seId = new SymbolEvidenceId(
                    pending.symbol().getSymbolId(),
                    pending.evidence().getEvidenceId()
            );
            symbolEvidenceBuffer.add(new SymbolEvidence(seId, pending.symbol(), pending.evidence()));
            if (symbolEvidenceBuffer.size() >= BATCH_SIZE) {
                symbolEvidenceSaved += flushSymbolEvidenceBuffer(symbolEvidenceBuffer);
            }
        }
        symbolEvidenceSaved += flushSymbolEvidenceBuffer(symbolEvidenceBuffer);

        log.info(
                "[GRAPHSTORE] symbol linking summary. runId={}, sourceFileLinked={}, moduleLinked={}",
                run.getRunId(),
                sourceFileLinkedCount,
                moduleLinkedCount
        );

        return new SymbolSaveResult(symbolMap, savedCount, symbolEvidenceSaved);
    }

    /**
     * symbol_evidence 저장 버퍼를 비우고 저장 건수를 반환한다.
     */
    private int flushSymbolEvidenceBuffer(List<SymbolEvidence> linkBuffer) {
        if (linkBuffer == null || linkBuffer.isEmpty()) {
            return 0;
        }

        int saved = linkBuffer.size();
        // 성능 최적화: symbol_evidence 전체 저장 리스트를 끝까지 들고 있지 않고, 변환 즉시 청크 단위로 저장 후 버퍼를 비운다.
        // 기존/신규 중복 key 판별은 앞 단계에서 그대로 수행하므로 GraphStore의 symbol 근거 연결 정확성은 유지된다.
        symbolEvidenceRepository.saveAll(linkBuffer);
        linkBuffer.clear();
        return saved;
    }

    /**
     * run 범위 symbol을 qualifiedName 기준 맵으로 구성한다.
     */
    private Map<String, SymbolEntity> loadExistingSymbolsByQualifiedName(RepoRun run) {
        Map<String, SymbolEntity> cache = new HashMap<>();
        List<SymbolEntity> existingSymbols = symbolRepository.findAllByRun_RunId(run.getRunId());
        for (SymbolEntity symbol : existingSymbols) {
            if (symbol.getQualifiedName() != null) {
                cache.putIfAbsent(symbol.getQualifiedName(), symbol);
            }
        }
        return cache;
    }

    private boolean shouldAssignSourceFile(SymbolEntity symbol, FileIndex sourceFile) {
        if (sourceFile == null) {
            return false;
        }
        if (symbol.getSourceFile() == null) {
            return true;
        }
        // .java/.kt 소스 파일이 .class 바이트코드보다 항상 우선한다
        boolean newIsSource = isSourceCodeFile(sourceFile.getPath());
        boolean currentIsSource = isSourceCodeFile(symbol.getSourceFile().getPath());
        if (newIsSource && !currentIsSource) {
            return true;   // .class → .java 업그레이드
        }
        if (!newIsSource && currentIsSource) {
            return false;  // .java를 .class로 덮어쓰지 않음
        }
        return !Objects.equals(symbol.getSourceFile().getFileId(), sourceFile.getFileId());
    }

    private boolean isSourceCodeFile(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".scala") || lower.endsWith(".groovy");
    }

    private boolean shouldAssignOwner(SymbolEntity current, SymbolEntity owner) {
        if (current.getOwner() == null) {
            return true;
        }
        return !Objects.equals(current.getOwner().getSymbolId(), owner.getSymbolId());
    }

    private boolean shouldAssignSourceSpan(SymbolEntity symbol, Evidence sourceEvidence) {
        return !Objects.equals(symbol.getSourceStartLine(), sourceEvidence.getStartLine())
                || !Objects.equals(symbol.getSourceEndLine(), sourceEvidence.getEndLine());
    }

    /**
     * evidenceIds 중 AST 타입이면서 startLine이 있는 evidence를 우선 선택한다.
     * 해당하는 것이 없으면 첫 번째로 조회된 evidence를 반환한다.
     */
    private Evidence selectSourceSpanEvidence(List<String> evidenceIds, Map<String, Evidence> evidenceMap) {
        Evidence fallback = null;
        for (String id : evidenceIds) {
            Evidence ev = evidenceMap.get(id);
            if (ev == null) {
                continue;
            }
            if (fallback == null) {
                fallback = ev;
            }
            if (ev.getEvidenceType() == EvidenceType.AST && ev.getStartLine() != null) {
                return ev;
            }
        }
        return fallback;
    }

    /**
     * 심볼의 evidence 중 AST 타입이면서 소스 코드 파일(.java/.kt 등)을 가리키는
     * evidence의 FileIndex를 선택한다. 없으면 null.
     * facts.json symbol.source_file은 .class(bytecode)로 기록되므로,
     * 실제 .java 경로를 보유한 AST evidence를 source file 승격에 사용한다.
     */
    private FileIndex selectSourceCodeFileIndex(List<String> evidenceIds, Map<String, Evidence> evidenceMap) {
        for (String id : evidenceIds) {
            Evidence ev = evidenceMap.get(id);
            if (ev == null || ev.getFile() == null) {
                continue;
            }
            if (ev.getEvidenceType() == EvidenceType.AST && isSourceCodeFile(ev.getFile().getPath())) {
                return ev.getFile();
            }
        }
        return null;
    }

    /**
     * run 범위 기존 symbol-evidence 연결 키를 메모리 Set으로 로드한다.
     */
    private Set<String> loadExistingSymbolEvidenceKeys(String runId) {
        List<SymbolEvidenceLinkKeyRow> existing =
                symbolEvidenceRepository.findLinkKeysByRunId(runId);
        Set<String> keys = new HashSet<>(Math.max(16, existing.size() * 2));
        for (SymbolEvidenceLinkKeyRow row : existing) {
            if (row.symbolId() != null && row.evidenceId() != null) {
                // 성능 최적화: 기존 symbol_evidence는 중복 방지 key만 필요하므로 엔티티 전체 로딩 없이 key 문자열만 구성한다.
                keys.add(row.symbolId() + ":" + row.evidenceId());
            }
        }
        return keys;
    }

    /**
     * run 범위 symbol-evidence에서 심볼 ID별 AST evidence를 한 건씩 추출한다.
     * startLine이 있는 AST evidence를 우선하여 저장한다.
     */
    private Map<String, Evidence> buildSymbolAstEvidenceMap(
            List<NormalizedSymbolFact> symbols,
            Map<String, SymbolEntity> symbolMap,
            Map<String, Evidence> evidenceMap
    ) {
        Map<String, Evidence> result = new HashMap<>();
        // 직전에 saveSymbols가 같은 facts 기준으로 symbol-evidence를 구성하므로
        // Edge 파생 근거 선택을 위해 symbol_evidence 테이블을 다시 조회하지 않는다.
        if (symbols == null || symbols.isEmpty()) {
            return result;
        }

        for (NormalizedSymbolFact symbolFact : symbols) {
            if (symbolFact == null || symbolFact.symbol() == null || symbolFact.evidenceIds() == null) {
                continue;
            }

            SymbolEntity symbol = symbolMap.get(symbolFact.symbol());
            if (symbol == null || symbol.getSymbolId() == null) {
                continue;
            }

            for (String factEvidenceId : symbolFact.evidenceIds()) {
                Evidence evidence = evidenceMap.get(factEvidenceId);
                if (evidence == null || evidence.getEvidenceType() != EvidenceType.AST) {
                    continue;
                }

                Evidence existing = result.get(symbol.getSymbolId());
                if (existing == null) {
                    result.put(symbol.getSymbolId(), evidence);
                } else if (evidence.getStartLine() != null && existing.getStartLine() == null) {
                    result.put(symbol.getSymbolId(), evidence);
                }
            }
        }
        return result;
    }

    /**
     * 멤버 심볼(method/field/constructor)의 ownerType을 역조회하는 맵을 구성한다.
     */
    private Map<String, String> buildSymbolToOwnerMap(List<NormalizedSymbolFact> symbols) {
        Map<String, String> result = new HashMap<>();
        for (NormalizedSymbolFact sym : symbols) {
            if (sym.symbol() == null || sym.ownerTypeSymbol() == null) {
                continue;
            }
            String kind = sym.kind() == null ? "" : sym.kind().toLowerCase();
            if ("method".equals(kind) || "field".equals(kind) || "constructor".equals(kind)) {
                result.put(sym.symbol(), sym.ownerTypeSymbol());
            }
        }
        return result;
    }

    /**
     * DERIVED 엣지에 연결할 evidence를 3단계 fallback으로 선택한다.
     * 1) from 심볼 직접 AST evidence
     * 2-A) CONTAINS(package→type): to 심볼 AST evidence
     * 2-B) 멤버(method/field/constructor): ownerType 심볼 AST evidence
     */
    private Evidence selectDerivedEdgeEvidence(
            NormalizedRelationFact dto,
            SymbolEntity from,
            SymbolEntity to,
            Map<String, Evidence> symbolAstEvidenceMap,
            Map<String, String> symbolToOwnerMap,
            Map<String, SymbolEntity> symbolMap
    ) {
        Evidence ev = symbolAstEvidenceMap.get(from.getSymbolId());
        if (ev != null) {
            return ev;
        }
        if ("CONTAINS".equalsIgnoreCase(dto.kind())
                && from.getSymbolKind() == SymbolKind.PACKAGE
                && to != null) {
            return symbolAstEvidenceMap.get(to.getSymbolId());
        }
        String ownerSymbol = symbolToOwnerMap.get(dto.srcSymbol());
        if (ownerSymbol != null) {
            SymbolEntity ownerEntity = symbolMap.get(ownerSymbol);
            if (ownerEntity != null) {
                return symbolAstEvidenceMap.get(ownerEntity.getSymbolId());
            }
        }
        return null;
    }

    private FileIndex resolveSymbolSourceFile(
            RepoRun run,
            NormalizedSymbolFact symbolFact,
            Map<String, FileIndex> sourceFileCache
    ) {
        if (symbolFact == null) {
            return null;
        }

        String normalizedPath = normalizeEvidencePath(symbolFact.sourceFile());
        if (normalizedPath == null) {
            return null;
        }

        return resolveFileIndex(run, normalizedPath, sourceFileCache);
    }

    /**
     * Relation을 Edge/EdgeEvidence로 적재한다.
     * 기존 edge/edge_evidence를 선조회하여 중복 판별 후 배치 저장한다.
     */
    private EdgeSaveResult saveEdges(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, SymbolEntity> symbolMap,
            Map<String, Evidence> evidenceMap
    ) {
        int edgesSaved = 0;
        int skippedRelations = 0;
        int resolvedByRawRef = 0;
        int resolvedBySimpleName = 0;

        Map<String, SymbolEntity> typeLookupIndex = buildTypeLookupIndex(symbolMap);
        // P1-1: IMPLEMENTS/EXTENDS 단순명 폴백 — 크로스모듈 동일 repo 타입 해소용
        Map<String, SymbolEntity> simpleNameInheritanceLookup = buildSimpleNameInheritanceLookup(symbolMap);
        Map<String, Evidence> symbolAstEvidenceMap = buildSymbolAstEvidenceMap(
                facts.symbols(),
                symbolMap,
                evidenceMap
        );
        Map<String, String> symbolToOwnerMap = buildSymbolToOwnerMap(facts.symbols());

        List<Edge> existingEdges = edgeRepository.findAllByRun_RunId(run.getRunId());
        Map<EdgeKey, Edge> edgeLookup = new HashMap<>(Math.max(16, existingEdges.size() * 2));
        for (Edge existing : existingEdges) {
            edgeLookup.putIfAbsent(toEdgeKey(existing), existing);
        }

        Set<String> existingEdgeEvidenceKeys = loadExistingEdgeEvidenceKeys(run.getRunId());
        List<Edge> newEdges = new ArrayList<>();
        List<PendingEdgeEvidence> pendingEdgeEvidence = new ArrayList<>();

        for (NormalizedRelationFact dto : facts.relations()) {
            if (dto.srcSymbol() == null || dto.srcSymbol().isBlank()) {
                skippedRelations++;
                continue;
            }

            SymbolEntity from = symbolMap.get(dto.srcSymbol());
            if (from == null) {
                skippedRelations++;
                continue;
            }

            SymbolEntity to = resolveDestinationSymbol(dto, symbolMap, typeLookupIndex, simpleNameInheritanceLookup);
            if (to != null
                    && (dto.dstSymbol() == null || dto.dstSymbol().isBlank())
                    && dto.dstRawRef() != null
                    && !dto.dstRawRef().isBlank()) {
                resolvedByRawRef++;
            }
            // P1-1: 단순명 폴백으로 해소된 EXTENDS/IMPLEMENTS 카운트
            if (to != null && isInheritanceEdge(dto.kind())
                    && !typeLookupIndex.containsKey(normalizeTypeRefForLookup(dto.dstSymbol()))) {
                resolvedBySimpleName++;
            }

            Edge candidate = factsEdgeConverter.toEntity(run, dto, from, to);
            EdgeKey edgeKey = toEdgeKey(from, candidate);
            Edge edge = edgeLookup.get(edgeKey);

            if (edge == null) {
                edge = candidate;
                edgeLookup.put(edgeKey, edge);
                newEdges.add(edge);
                edgesSaved++;
            }

            if (dto.evidenceIds() != null) {
                for (String factEvidenceId : dto.evidenceIds()) {
                    Evidence evidence = evidenceMap.get(factEvidenceId);
                    if (evidence == null || evidence.getEvidenceId() == null) {
                        continue;
                    }
                    pendingEdgeEvidence.add(new PendingEdgeEvidence(edge, evidence));
                }
            }

            if ("derived".equalsIgnoreCase(dto.derivation())
                    && (dto.evidenceIds() == null || dto.evidenceIds().isEmpty())) {

                Evidence derivedEvidence = selectDerivedEdgeEvidence(
                        dto,
                        from,
                        to,
                        symbolAstEvidenceMap,
                        symbolToOwnerMap,
                        symbolMap
                );

                if (derivedEvidence != null
                        && derivedEvidence.getEvidenceId() != null) {
                    pendingEdgeEvidence.add(
                            new PendingEdgeEvidence(edge, derivedEvidence)
                    );
                }
            }
        }

        saveAllInBatches(newEdges, BATCH_SIZE, edgeRepository::saveAll);

        Set<String> newEdgeEvidenceKeys = new HashSet<>();
        List<EdgeEvidence> linkBuffer = new ArrayList<>(BATCH_SIZE);
        int edgeEvidenceSaved = 0;
        for (PendingEdgeEvidence pending : pendingEdgeEvidence) {
            if (pending.edge() == null || pending.edge().getEdgeId() == null || pending.evidence() == null) {
                continue;
            }

            Long edgeId = pending.edge().getEdgeId();
            Long evidenceId = pending.evidence().getEvidenceId();
            String linkKey = toEdgeEvidenceLinkKey(edgeId, evidenceId);
            if (existingEdgeEvidenceKeys.contains(linkKey) || !newEdgeEvidenceKeys.add(linkKey)) {
                continue;
            }

            EdgeEvidenceId edgeEvidenceId = new EdgeEvidenceId(edgeId, evidenceId);
            linkBuffer.add(new EdgeEvidence(edgeEvidenceId, pending.edge(), pending.evidence()));
            if (linkBuffer.size() >= BATCH_SIZE) {
                edgeEvidenceSaved += flushEdgeEvidenceBuffer(linkBuffer);
            }
        }

        edgeEvidenceSaved += flushEdgeEvidenceBuffer(linkBuffer);

        log.info(
                "[GRAPHSTORE] relation linking summary. runId={}, totalRelations={}, resolvedByRawRef={}, resolvedBySimpleName={}, skippedRelations={}",
                run.getRunId(),
                facts.relations().size(),
                resolvedByRawRef,
                resolvedBySimpleName,
                skippedRelations
        );

        return new EdgeSaveResult(edgesSaved, edgeEvidenceSaved, skippedRelations);
    }

    /**
     * edge_evidence 저장 버퍼를 비우고 저장 건수를 반환한다.
     */
    private int flushEdgeEvidenceBuffer(List<EdgeEvidence> linkBuffer) {
        if (linkBuffer == null || linkBuffer.isEmpty()) {
            return 0;
        }

        int saved = linkBuffer.size();
        // 성능 최적화: edge_evidence 전체 저장 리스트를 끝까지 들고 있지 않고, 변환 즉시 청크 단위로 저장 후 버퍼를 비운다.
        // 중복 key 판별과 저장 조건은 그대로 유지하므로 GraphStore의 근거 연결 정확성은 바꾸지 않는다.
        edgeEvidenceRepository.saveAll(linkBuffer);
        linkBuffer.clear();
        return saved;
    }

    /**
     * Observation과 ObservationEvidence를 저장한다.
     *
     * 동일 run 재적재 시 FK 제약을 고려해 연결 테이블을 먼저 삭제하고,
     * 그 다음 Observation을 삭제한 뒤 새 데이터를 삽입한다.
     *
     * observations가 비어 있더라도 기존 데이터는 삭제하여
     * 이전 적재 결과가 stale 상태로 남지 않게 한다.
     */
    private int saveObservations(
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, Evidence> evidenceMap
    ) {
        String runId = run.getRunId();

        observationEvidenceRepository.deleteAllByRunId(
                runId
        );
        observationRepository.deleteAllByRunId(runId);

        List<NormalizedObservationFact> observations =
                facts.observations();

        if (observations == null || observations.isEmpty()) {
            log.info(
                    "[GRAPHSTORE] observations cleared. runId={}, count=0",
                    runId
            );
            return 0;
        }

        List<Observation> observationBuffer =
                new ArrayList<>(BATCH_SIZE);
        List<NormalizedObservationFact> factBuffer =
                new ArrayList<>(BATCH_SIZE);

        int observationsSaved = 0;
        int observationEvidenceSaved = 0;
        int missingEvidenceReferences = 0;
        int duplicateEvidenceReferences = 0;

        for (NormalizedObservationFact dto : observations) {
            observationBuffer.add(toObservation(run, dto));
            factBuffer.add(dto);

            if (observationBuffer.size() >= BATCH_SIZE) {
                ObservationChunkSaveResult result =
                        flushObservationBuffer(
                                observationBuffer,
                                factBuffer,
                                evidenceMap
                        );
                observationsSaved += result.observations();
                observationEvidenceSaved += result.observationEvidence();
                missingEvidenceReferences += result.missingEvidenceReferences();
                duplicateEvidenceReferences += result.duplicateEvidenceReferences();
            }
        }

        ObservationChunkSaveResult result =
                flushObservationBuffer(
                        observationBuffer,
                        factBuffer,
                        evidenceMap
                );
        observationsSaved += result.observations();
        observationEvidenceSaved += result.observationEvidence();
        missingEvidenceReferences += result.missingEvidenceReferences();
        duplicateEvidenceReferences += result.duplicateEvidenceReferences();

        log.info(
                "[GRAPHSTORE] observations saved. "
                        + "runId={}, observations={}, "
                        + "observationEvidence={}, "
                        + "missingEvidenceRefs={}, "
                        + "duplicateEvidenceRefs={}",
                runId,
                observationsSaved,
                observationEvidenceSaved,
                missingEvidenceReferences,
                duplicateEvidenceReferences
        );

        return observationsSaved;
    }

    private Observation toObservation(
            RepoRun run,
            NormalizedObservationFact dto
    ) {
        return new Observation(
                null,
                run,
                dto.kind(),
                dto.siteSymbol(),
                dto.targetSymbol(),
                dto.targetTypeRef(),
                dto.note(),
                dto.confidenceHint(),
                dto.attrs()
        );
    }

    private ObservationChunkSaveResult flushObservationBuffer(
            List<Observation> observationBuffer,
            List<NormalizedObservationFact> factBuffer,
            Map<String, Evidence> evidenceMap
    ) {
        if (observationBuffer == null || observationBuffer.isEmpty()) {
            return new ObservationChunkSaveResult(0, 0, 0, 0);
        }

        int observationCount = observationBuffer.size();

        // 성능 최적화: observation 전체 리스트와 observation_evidence 전체 리스트를 끝까지 보관하지 않는다.
        // 저장된 observation chunk와 같은 순서의 fact chunk를 즉시 연결해 기존 evidence 매칭 정확성은 유지한다.
        observationRepository.saveAll(observationBuffer);

        ObservationEvidenceLinkSupport.LinkBuildResult linkResult =
                ObservationEvidenceLinkSupport.build(
                        observationBuffer,
                        factBuffer,
                        evidenceMap
                );

        saveAllInBatches(
                linkResult.links(),
                BATCH_SIZE,
                observationEvidenceRepository::saveAll
        );

        int observationEvidenceCount = linkResult.links().size();
        int missingEvidenceReferences = linkResult.missingEvidenceReferences();
        int duplicateEvidenceReferences = linkResult.duplicateEvidenceReferences();

        observationBuffer.clear();
        factBuffer.clear();

        return new ObservationChunkSaveResult(
                observationCount,
                observationEvidenceCount,
                missingEvidenceReferences,
                duplicateEvidenceReferences
        );
    }

    /**
     * 기존 edge-evidence 연결 키를 메모리 Set으로 로드한다.
     * edge_id IN(...) 대신 run_id JOIN으로 조회해 PostgreSQL 65,535 파라미터 한도를 우회한다.
     */
    private Set<String> loadExistingEdgeEvidenceKeys(String runId) {
        List<EdgeEvidenceLinkKeyRow> existingLinks =
                edgeEvidenceRepository.findLinkKeysByRunId(runId);
        Set<String> keys = new HashSet<>(Math.max(16, existingLinks.size() * 2));
        for (EdgeEvidenceLinkKeyRow link : existingLinks) {
            if (link.edgeId() == null || link.evidenceId() == null) {
                continue;
            }
            // 성능 최적화: 기존 edge_evidence는 중복 방지 key만 필요하므로 엔티티 전체 로딩 없이 key 문자열만 구성한다.
            keys.add(toEdgeEvidenceLinkKey(link.edgeId(), link.evidenceId()));
        }
        return keys;
    }

    private String toEdgeEvidenceLinkKey(Long edgeId, Long evidenceId) {
        return edgeId + ":" + evidenceId;
    }

    private EdgeKey toEdgeKey(Edge edge) {
        String toSymbolId = edge.getToSymbol() == null ? null : edge.getToSymbol().getSymbolId();
        String toRawRefCanonical = toSymbolId == null ? canonicalJson(edge.getToRawRef()) : null;
        return new EdgeKey(
                edge.getFromSymbol().getSymbolId(),
                edge.getEdgeType(),
                toSymbolId,
                toRawRefCanonical
        );
    }

    private EdgeKey toEdgeKey(SymbolEntity from, Edge edge) {
        String toSymbolId = edge.getToSymbol() == null ? null : edge.getToSymbol().getSymbolId();
        String toRawRefCanonical = toSymbolId == null ? canonicalJson(edge.getToRawRef()) : null;
        return new EdgeKey(from.getSymbolId(), edge.getEdgeType(), toSymbolId, toRawRefCanonical);
    }

    /**
     * 리스트를 청크 단위로 saveAll 처리한다.
     */
    private <T> void saveAllInBatches(List<T> values, int batchSize, Consumer<List<T>> saver) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (int start = 0; start < values.size(); start += batchSize) {
            int end = Math.min(start + batchSize, values.size());
            saver.accept(values.subList(start, end));
        }
    }

    /**
     * 목적지 심볼을 우선 dstSymbol로 찾고, 없으면 dstRawRef를 기반으로 보조 해석한다.
     * P1-1: EXTENDS/IMPLEMENTS에 한해 단순명 폴백 인덱스를 최후 수단으로 사용한다.
     */
    private SymbolEntity resolveDestinationSymbol(
            NormalizedRelationFact relation,
            Map<String, SymbolEntity> symbolMap,
            Map<String, SymbolEntity> typeLookupIndex,
            Map<String, SymbolEntity> simpleNameInheritanceLookup
    ) {
        if (relation.dstSymbol() != null && !relation.dstSymbol().isBlank()) {
            SymbolEntity direct = symbolMap.get(relation.dstSymbol());
            if (direct != null) {
                return direct;
            }
            SymbolEntity fallbackFromDstSymbol = typeLookupIndex.get(normalizeTypeRefForLookup(relation.dstSymbol()));
            if (fallbackFromDstSymbol != null) {
                return fallbackFromDstSymbol;
            }
        }

        if (relation.dstRawRef() != null && !relation.dstRawRef().isBlank()) {
            for (String candidate : buildTypeLookupCandidates(relation.dstRawRef())) {
                SymbolEntity resolved = typeLookupIndex.get(candidate);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        // P1-1: EXTENDS/IMPLEMENTS 한정 단순명 폴백 — 크로스모듈 동일 repo 타입 해소
        if (!simpleNameInheritanceLookup.isEmpty() && isInheritanceEdge(relation.kind())) {
            String rawRef = relation.dstSymbol() != null && !relation.dstSymbol().isBlank()
                    ? relation.dstSymbol() : relation.dstRawRef();
            if (rawRef != null && !rawRef.isBlank()) {
                String simpleName = extractSimpleNameFromTypeRef(rawRef);
                if (simpleName != null) {
                    SymbolEntity resolved = simpleNameInheritanceLookup.get(simpleName);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }

        return null;
    }

    private Map<String, SymbolEntity> buildTypeLookupIndex(Map<String, SymbolEntity> symbolMap) {
        Map<String, SymbolEntity> index = new HashMap<>();

        for (Map.Entry<String, SymbolEntity> entry : symbolMap.entrySet()) {
            SymbolEntity symbol = entry.getValue();
            if (symbol == null || symbol.getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }

            addTypeLookupKey(index, entry.getKey(), symbol);
            addTypeLookupKey(index, symbol.getQualifiedName(), symbol);
        }

        return index;
    }

    /**
     * P1-1: IMPLEMENTS/EXTENDS 단순명 폴백 인덱스.
     * 동일 단순명을 가진 TYPE이 하나뿐인 경우만 등록(모호성 방지).
     * JDK/외부 라이브러리 타입은 symbolMap에 없으므로 자동으로 제외된다.
     */
    private Map<String, SymbolEntity> buildSimpleNameInheritanceLookup(Map<String, SymbolEntity> symbolMap) {
        Map<String, List<SymbolEntity>> candidates = new HashMap<>();
        for (SymbolEntity symbol : symbolMap.values()) {
            if (symbol.getSymbolKind() != SymbolKind.TYPE) continue;
            String simpleName = symbol.getSimpleName();
            if (simpleName != null && !simpleName.isBlank()) {
                candidates.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(symbol);
            }
        }
        Map<String, SymbolEntity> index = new HashMap<>();
        for (Map.Entry<String, List<SymbolEntity>> entry : candidates.entrySet()) {
            if (entry.getValue().size() == 1) {
                index.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        log.debug("[GRAPHSTORE-P1-1] simpleNameInheritanceLookup: unambiguous={}, total candidates={}",
                index.size(), candidates.size());
        return index;
    }

    private boolean isInheritanceEdge(String kind) {
        if (kind == null) return false;
        String upper = kind.trim().toUpperCase(Locale.ROOT);
        return "EXTENDS".equals(upper) || "IMPLEMENTS".equals(upper);
    }

    private String extractSimpleNameFromTypeRef(String typeRef) {
        String normalized = normalizeTypeRefForLookup(typeRef);
        if (normalized == null) return null;
        int dot = normalized.lastIndexOf('.');
        String simpleName = dot >= 0 ? normalized.substring(dot + 1) : normalized;
        return simpleName.isBlank() ? null : simpleName;
    }

    private List<String> buildTypeLookupCandidates(String raw) {
        String normalized = normalizeTypeRefForLookup(raw);
        if (normalized == null) {
            return List.of();
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);
        candidates.add(normalized.replace('$', '.'));
        candidates.add("type:" + normalized);
        candidates.add("type:" + normalized.replace('$', '.'));

        return List.copyOf(candidates);
    }

    private String normalizeTypeRefForLookup(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        value = value.replace('\\', '.').replace('/', '.');

        if (value.startsWith("type:")) {
            value = value.substring("type:".length());
        }

        if (value.startsWith("? extends ")) {
            value = value.substring("? extends ".length());
        } else if (value.startsWith("? super ")) {
            value = value.substring("? super ".length());
        } else if ("?".equals(value)) {
            return null;
        }

        value = stripTypeDecorations(value);
        value = stripGenericPart(value);

        while (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        if (value.endsWith("...")) {
            value = value.substring(0, value.length() - 3);
        }

        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private String stripTypeDecorations(String value) {
        String current = value.trim();
        for (String prefix : List.of("class ", "interface ", "enum ", "record ")) {
            if (current.startsWith(prefix)) {
                return current.substring(prefix.length()).trim();
            }
        }
        return current;
    }

    private String stripGenericPart(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') {
                depth++;
                continue;
            }
            if (c == '>') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0) {
                builder.append(c);
            }
        }
        return builder.toString().trim();
    }

    private void addTypeLookupKey(Map<String, SymbolEntity> index, String rawKey, SymbolEntity symbol) {
        String normalized = normalizeTypeRefForLookup(rawKey);
        if (normalized == null) {
            return;
        }

        for (String candidate : buildTypeLookupCandidates(normalized)) {
            index.putIfAbsent(candidate, symbol);
        }
    }

    private String canonicalJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private record EvidenceSaveResult(Map<String, Evidence> evidenceMap, int savedCount) {
    }

    private record SymbolSaveResult(Map<String, SymbolEntity> symbolMap, int savedCount, int symbolEvidenceSaved) {
    }

    private record EdgeSaveResult(int edgesSaved, int edgeEvidenceSaved, int skippedRelations) {
    }

    private record ObservationChunkSaveResult(
            int observations,
            int observationEvidence,
            int missingEvidenceReferences,
            int duplicateEvidenceReferences
    ) {
    }

    private record EvidenceLookupIndexes(
            Map<String, Evidence> rawIdLookup,
            Map<EvidenceIdentitySupport.Signature, Evidence>
                    signatureLookup
    ) {
    }

    private record EdgeKey(
            String fromSymbolId,
            EdgeType edgeType,
            String toSymbolId,
            String toRawRefCanonical
    ) {
    }

    private record PendingEdgeEvidence(Edge edge, Evidence evidence) {
    }

    private record PendingSymbolEvidence(SymbolEntity symbol, Evidence evidence) {
    }
}
