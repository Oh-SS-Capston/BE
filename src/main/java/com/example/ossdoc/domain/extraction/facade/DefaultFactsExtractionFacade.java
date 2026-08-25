package com.example.ossdoc.domain.extraction.facade;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionFacadeContext;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.ChunkingPolicy;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ModuleRef;
import com.example.ossdoc.domain.extraction.dto.model.ModuleMergeResult;
import com.example.ossdoc.domain.extraction.dto.model.RootMergeResult;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.enums.BuildStatus;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.ChunkStatus;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.exception.ExtractionErrorCode;
import com.example.ossdoc.domain.extraction.exception.ExtractionException;
import com.example.ossdoc.domain.extraction.service.composer.FactsComposer;
import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.service.extractor.ChunkFactsExtractionCoordinator;
import com.example.ossdoc.domain.extraction.service.extractor.ExtractionRunCache;
import com.example.ossdoc.domain.extraction.service.extractor.MetaInfServiceScanner;
import com.example.ossdoc.domain.extraction.service.extractor.ReadmeObservationScanner;
import com.example.ossdoc.domain.build.support.RepoRootResolver;
import com.example.ossdoc.domain.extraction.service.support.preflight.BuildManifestLoader;
import com.example.ossdoc.domain.extraction.service.support.planning.ChunkPlanner;
import com.example.ossdoc.domain.extraction.service.support.util.ExtractionClock;
import com.example.ossdoc.domain.extraction.service.support.merge.ExtractionMergeSupport;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightChecker;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightResult;
import com.example.ossdoc.domain.extraction.service.support.util.FactsSchema;
import com.example.ossdoc.domain.extraction.service.support.util.RepoPathUtils;
import com.example.ossdoc.domain.extraction.service.support.util.WarningCollector;
import com.example.ossdoc.domain.extraction.dto.context.FactsWriteContext;
import com.example.ossdoc.domain.extraction.service.writer.FactsWriter;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultFactsExtractionFacade implements FactsExtractionFacade {


    private final ObjectMapper objectMapper;
    private final ExtractionClock extractionClock;
    private final ExtractionPreflightChecker extractionPreflightChecker;
    private final ChunkPlanner chunkPlanner;
    private final ChunkFactsExtractionCoordinator chunkFactsExtractionCoordinator;
    private final ExtractionMergeSupport extractionMergeSupport;
    private final MetaInfServiceScanner metaInfServiceScanner;
    private final ReadmeObservationScanner readmeObservationScanner;
    private final FactsComposer factsComposer;
    private final FactsWriter factsWriter;
    private final RepoRunRepository repoRunRepository;
    private final RepoRootResolver repoRootResolver;
    private final BuildManifestLoader buildManifestLoader;

    @Override
    public FactsExtractResponse extract(FactsExtractRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        OffsetDateTime startedAt = extractionClock.now();
        WarningCollector warnings = new WarningCollector();

        ExtractionFacadeContext prepared = prepareFacadeContext(request, startedAt, warnings);
        log.info("[EXTRACTION] Phase 1 완료 — 컨텍스트 준비 (runId={}, repoRoot={})", prepared.runId(), prepared.repoRoot());

        ExtractionPreflightResult preflightResult = extractionPreflightChecker.check(
                request,
                prepared.buildManifest(),
                prepared.repoRoot()
        );
        warnings.addAll(preflightResult.warnings());
        log.info("[EXTRACTION] Phase 2 완료 — Preflight 검사 (mode={}, bytecodeAvailability={})",
                preflightResult.resolvedMode(),
                preflightResult.bytecodeAvailability() == null ? "N/A" : preflightResult.bytecodeAvailability().availability());

        ExtractionFacadeContext facadeContext = ExtractionFacadeContext.builder()
                .request(prepared.request())
                .runId(prepared.runId())
                .startedAt(prepared.startedAt())
                .finishedAt(prepared.finishedAt())
                .workspaceRoot(prepared.workspaceRoot())
                .repoRoot(prepared.repoRoot())
                .artifactsRoot(prepared.artifactsRoot())
                .buildManifestPath(prepared.buildManifestPath())
                .buildManifest(prepared.buildManifest())
                .preflightResult(preflightResult)
                .chunkingPolicy(prepared.chunkingPolicy())
                .jobMeta(prepared.jobMeta())
                .buildMeta(buildBuildMeta(prepared.buildManifest(), preflightResult, prepared.repoRoot(), preflightResult.resolvedMode()))
                .engineVersions(prepared.engineVersions())
                .build();

        ensureProceedable(facadeContext, warnings);

        ChunkingPolicy chunkingPolicy = facadeContext.chunkingPolicy() == null
                ? ChunkingPolicy.standard()
                : facadeContext.chunkingPolicy();

        List<ChunkDescriptor> chunks = chunkPlanner.plan(
                request,
                preflightResult,
                facadeContext.repoRoot(),
                chunkingPolicy
        );

        log.info("[EXTRACTION] Phase 3 완료 — 청크 계획 (chunks={})", chunks.size());

        if (chunks.isEmpty()) {
            warnings.add("No extraction chunks were planned; resulting facts.json will be structurally valid but empty");
        }

        List<ChunkResult> chunkResults = executeChunksInParallel(
                request,
                preflightResult,
                facadeContext.repoRoot(),
                chunks,
                chunkingPolicy,
                warnings
        );

        long successCount = 0L;
        long failedCount = 0L;
        // chunk 수가 많을 때 상태 집계를 위해 같은 결과 목록을 두 번 순회하지 않는다.
        for (ChunkResult chunkResult : chunkResults) {
            if (chunkResult.status() == ChunkStatus.SUCCEEDED) {
                successCount++;
            } else if (chunkResult.status() == ChunkStatus.FAILED) {
                failedCount++;
            }
        }
        log.info("[EXTRACTION] Phase 4 완료 — 병렬 추출 (total={}, success={}, failed={})",
                chunkResults.size(), successCount, failedCount);

        ExtractionAggregate aggregate = buildAggregate(chunkResults);
        warnings.addAll(aggregate.warnings());
        log.info("[EXTRACTION] Phase 5 완료 — 병합 (evidence={}, warnings={})",
                aggregate.evidence().size(), aggregate.warnings().size());

        // META-INF/services SPI 스캔 (청크 병합 완료 후, composer 직전)
        List<BuildModuleManifest> buildModules = facadeContext.buildManifest() != null
                ? facadeContext.buildManifest().getModules()
                : List.of();
        ExtractionSink resourceSink = new ExtractionSink();
        metaInfServiceScanner.scan(facadeContext.repoRoot(), buildModules, resourceSink);
        ChunkDescriptor resourceChunk = ChunkDescriptor.builder()
                .chunkId("meta-inf-services")
                .kind(ChunkKind.AST)
                .module("resource")
                .rootPath("")
                .files(List.of())
                .totalBytes(0)
                .fileCount(0)
                .build();
        aggregate = extractionMergeSupport.mergeChunkIntoAggregate(
                aggregate, resourceSink.toChunkResult(resourceChunk));
        log.info("[EXTRACTION] Phase 5-1 완료 — META-INF/services 스캔");

        // README 스캔 (청크 병합 + META-INF 스캔 완료 후, public surface 확보된 시점)
        ExtractionSink readmeSink = new ExtractionSink();
        readmeObservationScanner.scan(facadeContext.repoRoot(), aggregate, readmeSink);
        ChunkDescriptor readmeChunk = ChunkDescriptor.builder()
                .chunkId("readme-scan")
                .kind(ChunkKind.AST)
                .module("resource")
                .rootPath("")
                .files(List.of())
                .totalBytes(0)
                .fileCount(0)
                .build();
        aggregate = extractionMergeSupport.mergeChunkIntoAggregate(
                aggregate, readmeSink.toChunkResult(readmeChunk));
        log.info("[EXTRACTION] Phase 5-2 완료 — README 스캔");

        OffsetDateTime finishedAt = extractionClock.now();

        FactsDocument factsDocument = factsComposer.compose(new FactsCompositionContext(
                FactsSchema.SCHEMA_VERSION,
                facadeContext.jobMeta(),
                facadeContext.buildMeta(),
                preflightResult.resolvedMode(),
                facadeContext.startedAt(),
                finishedAt,
                warnings.snapshot(),
                request.includeObservations(),
                aggregate
        ));

        log.info("[EXTRACTION] Phase 6 완료 — FactsDocument 구성");

        FactsWriteContext writeContext = new FactsWriteContext(
                facadeContext.runId(),
                facadeContext.artifactsRoot(),
                factsDocument,
                warnings.snapshot()
        );

        FactsExtractResponse response = writeFactsAndBuildResponse(writeContext);
        log.info("[EXTRACTION] Phase 7 완료 — facts.json 저장 완료 (runId={})", facadeContext.runId());

        List<String> finalWarnings = response.warnings();
        if (finalWarnings != null && !finalWarnings.isEmpty()) {
            finalWarnings.forEach(w -> log.warn("[EXTRACTION] Warning: {}", w));
        }

        return response;
    }

    private ExtractionFacadeContext prepareFacadeContext(
            FactsExtractRequest request,
            OffsetDateTime startedAt,
            WarningCollector warnings
    ) {
        String runId = request.runId();

        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new ExtractionException(ExtractionErrorCode.RUN_NOT_FOUND));

        Path workspaceRoot = Path.of(run.getWorkspaceRoot()).normalize();
        if (!Files.isDirectory(workspaceRoot)) {
            throw new ExtractionException(ExtractionErrorCode.WORKSPACE_NOT_FOUND,
                    "workspace root does not exist: " + workspaceRoot);
        }

        JobMeta jobMeta = JobMeta.builder()
                .jobId(runId)
                .repoUrl(run.getRepoUrl())
                .commitSha(run.getCommitSha())
                .build();

        Path repoRoot = resolveRepoRoot(workspaceRoot);
        Path artifactsRoot = workspaceRoot.resolve("artifacts").normalize();

        BuildManifest buildManifest = buildManifestLoader.load(artifactsRoot);
        if (buildManifest == null) {
            warnings.add("build_manifest could not be loaded from local artifacts for runId=" + runId);
        }

        Map<String, String> engineVersions = new LinkedHashMap<>();
        engineVersions.put("ast", "javaparser");
        engineVersions.put("bytecode", "asm");

        return ExtractionFacadeContext.builder()
                .request(request)
                .runId(runId)
                .startedAt(startedAt)
                .workspaceRoot(workspaceRoot)
                .repoRoot(repoRoot)
                .artifactsRoot(artifactsRoot)
                .buildManifestPath(null)
                .buildManifest(buildManifest)
                .chunkingPolicy(ChunkingPolicy.standard())
                .jobMeta(jobMeta)
                .buildMeta(BuildMeta.builder()
                        .status(BuildStatus.FAILED.code())
                        .tool(null)
                        .javaVersionUsed(System.getProperty("java.specification.version"))
                        .classpathFingerprint(null)
                        .asmUnavailable(true)
                        .modules(List.of())
                        .build())
                .engineVersions(engineVersions)
                .build();
    }

    private void ensureProceedable(ExtractionFacadeContext facadeContext, WarningCollector warnings) {
        ExtractionPreflightResult preflightResult = facadeContext.preflightResult();
        if (preflightResult != null && preflightResult.canProceed()) {
            return;
        }

        List<String> blockingReasons = preflightResult == null
                ? List.of("preflight result is missing")
                : preflightResult.blockingReasons();
        warnings.addAll(blockingReasons);
        log.error("[EXTRACTION] Preflight 실패 — 진행 불가 (reasons={})", String.join("; ", blockingReasons));

        throw new ExtractionException(
                ExtractionErrorCode.EXTRACTION_PREFLIGHT_FAILED,
                buildExceptionDetail(facadeContext.runId(), String.join("; ", blockingReasons), warnings)
        );
    }

    private List<ChunkResult> executeChunksInParallel(
            FactsExtractRequest request,
            ExtractionPreflightResult preflightResult,
            Path repoRoot,
            List<ChunkDescriptor> chunks,
            ChunkingPolicy chunkingPolicy,
            WarningCollector warnings
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        boolean failFast = request != null && request.failFast();
        ExtractionRunCache runCache = new ExtractionRunCache();

        ExecutorService executor = Executors.newFixedThreadPool(
                chunkingPolicy.workerCount(),
                new ExtractionWorkerThreadFactory()
        );

        ExecutorCompletionService<ChunkExecutionOutcome> completionService =
                new ExecutorCompletionService<>(executor);

        try {
            int submitted = 0;
            for (ChunkDescriptor chunk : chunks) {
                completionService.submit(() -> executeSingleChunk(
                        request,
                        preflightResult,
                        repoRoot,
                        chunk,
                        runCache
                ));
                submitted++;
            }

            List<ChunkResult> results = new ArrayList<>();

            for (int i = 0; i < submitted; i++) {
                Future<ChunkExecutionOutcome> completedFuture;
                try {
                    completedFuture = completionService.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelPending(executor);
                    throw new ExtractionException(
                            ExtractionErrorCode.EXTRACTION_ORCHESTRATION_INTERRUPTED,
                            buildExceptionDetail(request != null ? request.runId() : "<unknown>",
                                    "chunk result collection was interrupted", warnings),
                            e
                    );
                }

                ChunkExecutionOutcome outcome;
                try {
                    outcome = completedFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelPending(executor);
                    throw new ExtractionException(
                            ExtractionErrorCode.EXTRACTION_ORCHESTRATION_INTERRUPTED,
                            buildExceptionDetail(request != null ? request.runId() : "<unknown>",
                                    "waiting for a completed chunk result was interrupted", warnings),
                            e
                    );
                } catch (ExecutionException e) {
                    cancelPending(executor);
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new ExtractionException(
                            ExtractionErrorCode.EXTRACTION_ORCHESTRATION_FAILED,
                            buildExceptionDetail(request != null ? request.runId() : "<unknown>",
                                    "failed to retrieve completed chunk extraction result: " + safeMessage(cause), warnings),
                            cause
                    );
                }

                if (outcome.executionFailed()) {
                    if (failFast) {
                        cancelPending(executor);
                        throw failFastAbort(outcome.chunk(), outcome.failureMessage());
                    }
                    results.add(failedChunk(outcome.chunk(), outcome.failureMessage()));
                    continue;
                }

                ChunkResult chunkResult = outcome.chunkResult();
                if (chunkResult == null) {
                    if (failFast) {
                        cancelPending(executor);
                        throw failFastAbort(outcome.chunk(), "chunk extraction returned null result");
                    }
                    results.add(failedChunk(outcome.chunk(), "chunk extraction returned null result"));
                    continue;
                }

                if (failFast && shouldAbortOnChunkFailure(chunkResult)) {
                    cancelPending(executor);
                    throw failFastAbort(chunkResult.descriptor(), firstFailureMessage(chunkResult));
                }

                log.debug("[EXTRACTION] 청크 완료: {} (status={})",
                        chunkResult.descriptor() != null ? chunkResult.descriptor().chunkId() : "<unknown>",
                        chunkResult.status());
                results.add(chunkResult);
            }

            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
        }
    }

    private ChunkExecutionOutcome executeSingleChunk(
            FactsExtractRequest request,
            ExtractionPreflightResult preflightResult,
            Path repoRoot,
            ChunkDescriptor chunk,
            ExtractionRunCache runCache
    ) {
        try {
            ChunkResult chunkResult = chunkFactsExtractionCoordinator.extract(
                    request,
                    preflightResult,
                    repoRoot,
                    chunk,
                    runCache
            );
            return ChunkExecutionOutcome.success(chunk, chunkResult);
        } catch (Exception e) {
            return ChunkExecutionOutcome.failure(chunk, safeMessage(e));
        }
    }

    private boolean shouldAbortOnChunkFailure(ChunkResult chunkResult) {
        if (chunkResult == null) {
            return true;
        }

        if (chunkResult.status() == ChunkStatus.FAILED || chunkResult.status() == ChunkStatus.PARTIAL) {
            return true;
        }

        return chunkResult.errors() != null && !chunkResult.errors().isEmpty();
    }

    private String firstFailureMessage(ChunkResult chunkResult) {
        if (chunkResult == null) {
            return "chunk extraction returned null result";
        }
        if (chunkResult.errors() != null && !chunkResult.errors().isEmpty()) {
            return chunkResult.errors().get(0);
        }
        if (chunkResult.status() == ChunkStatus.PARTIAL) {
            return "chunk extraction produced partial result";
        }
        if (chunkResult.status() == ChunkStatus.FAILED) {
            return "chunk extraction failed";
        }
        return "chunk extraction aborted";
    }

    private ExtractionException failFastAbort(ChunkDescriptor chunk, String message) {
        String chunkId = chunk == null || chunk.chunkId() == null || chunk.chunkId().isBlank()
                ? "<unknown-chunk>"
                : chunk.chunkId();

        String detail = (message == null || message.isBlank())
                ? "unknown chunk extraction failure"
                : message;

        return new ExtractionException(
                ExtractionErrorCode.EXTRACTION_FAIL_FAST_ABORTED,
                "chunkId=" + chunkId + ", reason=" + detail
        );
    }

    private void cancelPending(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static String buildExceptionDetail(String runId, String reason, WarningCollector warnings) {
        List<String> allWarnings = warnings.snapshot();
        return "runId=" + runId
                + ", reason=" + reason
                + (allWarnings.isEmpty() ? "" : ", warnings=" + String.join("; ", allWarnings));
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return (message == null || message.isBlank()) ? throwable.toString() : message;
    }

    private ChunkResult failedChunk(ChunkDescriptor chunk, String message) {
        return ChunkResult.builder()
                .descriptor(chunk)
                .status(ChunkStatus.FAILED)
                .evidence(Map.of())
                .symbols(List.of())
                .relations(List.of())
                .observations(List.of())
                .stats(StatsMeta.builder()
                        .filesScanned(chunk == null ? 0L : chunk.fileCount())
                        .errors(1L)
                        .build())
                .warnings(List.of())
                .errors(message == null || message.isBlank()
                        ? List.of("unknown chunk extraction failure")
                        : List.of(message))
                .build();
    }

    private ExtractionAggregate buildAggregate(List<ChunkResult> chunkResults) {
        if (chunkResults == null || chunkResults.isEmpty()) {
            return extractionMergeSupport.aggregate(List.of());
        }

        Map<String, Map<String, List<ChunkResult>>> groupedByModuleAndRoot = new LinkedHashMap<>();
        for (ChunkResult chunkResult : chunkResults) {
            if (chunkResult == null || chunkResult.descriptor() == null) {
                continue;
            }
            String module = chunkResult.descriptor().module();
            String rootPath = chunkResult.descriptor().rootPath();
            groupedByModuleAndRoot
                    .computeIfAbsent(module, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(rootPath, ignored -> new ArrayList<>())
                    .add(chunkResult);
        }

        List<ModuleMergeResult> modules = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<ChunkResult>>> moduleEntry : groupedByModuleAndRoot.entrySet()) {
            List<RootMergeResult> roots = new ArrayList<>();
            for (Map.Entry<String, List<ChunkResult>> rootEntry : moduleEntry.getValue().entrySet()) {
                roots.add(extractionMergeSupport.mergeRoot(moduleEntry.getKey(), rootEntry.getKey(), rootEntry.getValue()));
            }
            modules.add(extractionMergeSupport.mergeModule(moduleEntry.getKey(), roots));
        }

        return extractionMergeSupport.aggregate(modules);
    }

    private BuildMeta buildBuildMeta(
            BuildManifest buildManifest,
            ExtractionPreflightResult preflightResult,
            Path repoRoot,
            ExtractionMode mode
    ) {
        boolean asmUnavailable = mode == null
                || mode == ExtractionMode.AST_ONLY
                || mode == ExtractionMode.AST_PLUS_PARTIAL_BYTECODE;

        if (buildManifest == null) {
            return BuildMeta.builder()
                    .status(BuildStatus.FAILED.code())
                    .tool(null)
                    .javaVersionUsed(System.getProperty("java.specification.version"))
                    .classpathFingerprint(null)
                    .asmUnavailable(true)
                    .modules(List.of())
                    .build();
        }

        List<BuildModuleManifest> modules = buildManifest.getModules() == null ? List.of() : buildManifest.getModules();
        List<ModuleRef> moduleRefs = modules.stream()
                .map(m -> ModuleRef.builder()
                        .name(safeModuleId(m))
                        .path(null)
                        .build())
                .distinct()
                .toList();

        String buildMode = resolveBuildMode(buildManifest);
        String status = resolveBuildStatus(buildMode);
        String tool = buildManifest.getBuildTool() == null ? null : buildManifest.getBuildTool().name();

        return BuildMeta.builder()
                .status(status)
                .tool(tool)
                .javaVersionUsed(System.getProperty("java.specification.version"))
                .classpathFingerprint(classpathFingerprint(preflightResult))
                .asmUnavailable(asmUnavailable)
                .modules(moduleRefs)
                .build();
    }

    private String resolveBuildMode(BuildManifest buildManifest) {
        if (buildManifest == null || buildManifest.getBuildMode() == null) {
            return null;
        }
        return buildManifest.getBuildMode().name();
    }

    private String resolveBuildStatus(String buildMode) {
        if (buildMode == null || buildMode.isBlank()) {
            return BuildStatus.FAILED.code();
        }

        return switch (buildMode) {
            case "FULL", "COMPILE_ONLY" -> BuildStatus.SUCCESS.code();
            case "SOURCE_ONLY" -> BuildStatus.PARTIAL.code();
            case "FAILED" -> BuildStatus.FAILED.code();
            case "SKIPPED" -> BuildStatus.FAILED.code();
            default -> BuildStatus.FAILED.code();
        };
    }

    private String classpathFingerprint(ExtractionPreflightResult preflightResult) {
        if (preflightResult == null || preflightResult.normalizedContext() == null) {
            return null;
        }
        String joined = preflightResult.normalizedContext().compileClasspath().stream()
                .map(Path::toString)
                .collect(Collectors.joining("|"));
        if (joined.isBlank()) {
            return null;
        }
        return Integer.toHexString(joined.hashCode());
    }

    private List<String> scannedModules(ExtractionPreflightResult preflightResult) {
        if (preflightResult == null || preflightResult.normalizedContext() == null) {
            return List.of();
        }
        return preflightResult.normalizedContext().modules().stream()
                .map(ExtractionPreflightResult.ModuleNormalizedContext::moduleName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> scannedSourceRoots(ExtractionPreflightResult preflightResult, Path repoRoot) {
        if (preflightResult == null || preflightResult.normalizedContext() == null) {
            return List.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (ExtractionPreflightResult.ModuleNormalizedContext module : preflightResult.normalizedContext().modules()) {
            for (Path sourceRoot : safePaths(module.sourceRoots())) {
                values.add(RepoPathUtils.toRepoRelative(repoRoot, sourceRoot));
            }
            for (Path testRoot : safePaths(module.testRoots())) {
                values.add(RepoPathUtils.toRepoRelative(repoRoot, testRoot));
            }
        }
        return List.copyOf(values);
    }

    private List<String> scannedBytecodeRoots(ExtractionPreflightResult preflightResult, Path repoRoot) {
        if (preflightResult == null || preflightResult.normalizedContext() == null) {
            return List.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (ExtractionPreflightResult.ModuleNormalizedContext module : preflightResult.normalizedContext().modules()) {
            for (Path classesDir : safePaths(module.classesDirs())) {
                values.add(RepoPathUtils.toRepoRelative(repoRoot, classesDir));
            }
        }
        return List.copyOf(values);
    }

    private List<Path> safePaths(List<Path> paths) {
        return paths == null ? List.of() : paths;
    }

    private FactsExtractResponse writeFactsAndBuildResponse(FactsWriteContext writeContext) {
        return factsWriter.writeAndBuildResponse(writeContext);
    }

    private Path resolveRepoRoot(Path workspaceRoot) {
        Path repoRoot = workspaceRoot.resolve("repo");
        return repoRootResolver.resolveActualRoot(repoRoot).normalize();
    }

    private String normalizeRepoRelative(Path repoRoot, String rawPath) {
        Path path = Path.of(rawPath);
        Path normalized = path.isAbsolute() ? path.normalize() : repoRoot.resolve(path).normalize();
        return RepoPathUtils.toRepoRelative(repoRoot, normalized);
    }

    private String safeModuleId(BuildModuleManifest module) {
        if (module == null || module.getModuleId() == null || module.getModuleId().isBlank()) {
            return "<unknown-module>";
        }
        return module.getModuleId();
    }

    private static final class ExtractionWorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "facts-extractor-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private record ChunkExecutionOutcome(
            ChunkDescriptor chunk,
            ChunkResult chunkResult,
            boolean executionFailed,
            String failureMessage
    ) {
        private static ChunkExecutionOutcome success(ChunkDescriptor chunk, ChunkResult chunkResult) {
            return new ChunkExecutionOutcome(chunk, chunkResult, false, null);
        }

        private static ChunkExecutionOutcome failure(ChunkDescriptor chunk, String failureMessage) {
            return new ChunkExecutionOutcome(chunk, null, true, failureMessage);
        }
    }
}
