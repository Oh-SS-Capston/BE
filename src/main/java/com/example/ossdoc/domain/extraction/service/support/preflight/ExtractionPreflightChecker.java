package com.example.ossdoc.domain.extraction.service.support.preflight;
import com.example.ossdoc.domain.extraction.service.support.util.WarningCollector;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ExtractionPreflightChecker {

    private final BuildOutputVerifier buildOutputVerifier;
    private final BytecodeAvailabilityChecker bytecodeAvailabilityChecker;
    private final ExtractionModeResolver extractionModeResolver;

    public ExtractionPreflightResult check(
            FactsExtractRequest request,
            BuildManifest buildManifest,
            Path repoRoot
    ) {
        BuildOutputVerifier.BuildOutputVerificationResult buildVerification =
                buildOutputVerifier.verify(buildManifest);

        BytecodeAvailabilityChecker.BytecodeAvailabilityResult bytecodeAvailability =
                bytecodeAvailabilityChecker.check(buildManifest, repoRoot);

        ExtractionModeResolver.ExtractionModeResolutionResult modeResolution =
                extractionModeResolver.resolve(
                        request,
                        buildManifest,
                        buildVerification,
                        bytecodeAvailability
                );

        PathNormalizationResult normalizationResult = normalizeContext(buildManifest, repoRoot, request);

        WarningCollector warnings = new WarningCollector();
        warnings.addAll(buildVerification.warnings());
        warnings.addAll(bytecodeAvailability.warnings());
        warnings.addAll(modeResolution.warnings());
        warnings.addAll(normalizationResult.warnings());

        List<String> blockingReasons = collectBlockingReasons(
                buildVerification,
                modeResolution,
                normalizationResult.context(),
                bytecodeAvailability
        );

        boolean canProceed = blockingReasons.isEmpty();

        return new ExtractionPreflightResult(
                buildManifest,
                buildVerification,
                bytecodeAvailability,
                modeResolution,
                normalizationResult.context(),
                warnings.snapshot(),
                blockingReasons,
                canProceed
        );
    }

    private List<String> collectBlockingReasons(
            BuildOutputVerifier.BuildOutputVerificationResult buildVerification,
            ExtractionModeResolver.ExtractionModeResolutionResult modeResolution,
            ExtractionPreflightResult.NormalizedExtractionContext normalizedContext,
            BytecodeAvailabilityChecker.BytecodeAvailabilityResult bytecodeAvailability
    ) {
        List<String> blockingReasons = new ArrayList<>();

        if (buildVerification == null || !buildVerification.manifestExists()) {
            blockingReasons.add("build_manifest is missing");
        }

        if (buildVerification == null || !buildVerification.buildUsable()) {
            blockingReasons.add("build metadata is not usable for extraction");
        }

        if (modeResolution == null || modeResolution.resolvedMode() == null) {
            blockingReasons.add("extraction mode could not be resolved");
        }

        if (normalizedContext == null || !normalizedContext.hasAnyJavaRoots()) {
            blockingReasons.add("no usable sourceRoots/testRoots were found after normalization");
        }

        ExtractionMode resolvedMode = modeResolution == null ? null : modeResolution.resolvedMode();
        boolean requiresBytecode = resolvedMode == ExtractionMode.AST_PLUS_BYTECODE
                || resolvedMode == ExtractionMode.AST_PLUS_PARTIAL_BYTECODE;

        if (requiresBytecode && (bytecodeAvailability == null || !bytecodeAvailability.hasUsableBytecode())) {
            blockingReasons.add("resolved mode requires bytecode but no usable .class files were found");
        }

        return List.copyOf(blockingReasons);
    }

    private PathNormalizationResult normalizeContext(
            BuildManifest buildManifest,
            Path repoRoot,
            FactsExtractRequest request
    ) {
        WarningCollector warnings = new WarningCollector();

        if (buildManifest == null) {
            warnings.add("Cannot normalize extraction paths because build_manifest is null");
            return new PathNormalizationResult(
                    ExtractionPreflightResult.NormalizedExtractionContext.empty(),
                    warnings.snapshot()
            );
        }

        if (repoRoot == null) {
            warnings.add("Cannot normalize extraction paths because repoRoot is null");
            return new PathNormalizationResult(
                    ExtractionPreflightResult.NormalizedExtractionContext.empty(),
                    warnings.snapshot()
            );
        }

        List<ExtractionPreflightResult.ModuleNormalizedContext> modules = new ArrayList<>();
        Set<Path> compileClasspath = new LinkedHashSet<>();
        Set<Path> runtimeClasspath = new LinkedHashSet<>();

        for (BuildModuleManifest module : safeModules(buildManifest.getModules())) {
            String moduleName = safeModuleId(module);

            if (!isTargetModule(request, moduleName)) {
                continue;
            }

            Set<Path> sourceRoots = new LinkedHashSet<>();
            Set<Path> testRoots = new LinkedHashSet<>();
            Set<Path> resourceRoots = new LinkedHashSet<>();
            Set<Path> classesDirs = new LinkedHashSet<>();

            normalizeDirectoryList(repoRoot, module.getSourceRoots(), sourceRoots, warnings, "sourceRoots", module);
            normalizeDirectoryList(repoRoot, module.getTestRoots(), testRoots, warnings, "testRoots", module);
            normalizeDirectoryList(repoRoot, module.getResourceRoots(), resourceRoots, warnings, "resourceRoots", module);
            normalizeDirectoryList(repoRoot, module.getClassesDirs(), classesDirs, warnings, "classesDirs", module);

            normalizeClasspathList(repoRoot, module.getCompileClasspath(), compileClasspath, warnings, "compileClasspath", module);
            normalizeClasspathList(repoRoot, module.getRuntimeClasspath(), runtimeClasspath, warnings, "runtimeClasspath", module);

            modules.add(new ExtractionPreflightResult.ModuleNormalizedContext(
                    moduleName,
                    List.copyOf(sourceRoots),
                    List.copyOf(testRoots),
                    List.copyOf(resourceRoots),
                    List.copyOf(classesDirs)
            ));
        }

        ExtractionPreflightResult.NormalizedExtractionContext context =
                new ExtractionPreflightResult.NormalizedExtractionContext(
                        List.copyOf(modules),
                        List.copyOf(compileClasspath),
                        List.copyOf(runtimeClasspath)
                );

        return new PathNormalizationResult(context, warnings.snapshot());
    }

    private boolean isTargetModule(FactsExtractRequest request, String moduleName) {
        if (request == null || request.targetModules() == null || request.targetModules().isEmpty()) {
            return true;
        }
        return request.targetModules().stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(moduleName::equals);
    }

    private void normalizeDirectoryList(
            Path repoRoot,
            List<String> rawPaths,
            Set<Path> target,
            WarningCollector warnings,
            String fieldName,
            BuildModuleManifest module
    ) {
        if (rawPaths == null) {
            return;
        }

        for (String rawPath : rawPaths) {
            if (rawPath == null || rawPath.isBlank()) {
                warnings.add("Blank " + fieldName + " entry found in module: " + safeModuleId(module));
                continue;
            }

            try {
                Path normalized = normalizePath(repoRoot, rawPath);
                if (!Files.isDirectory(normalized)) {
                    warnings.add(fieldName + " path does not exist or is not a directory: " + normalized);
                    continue;
                }
                target.add(normalized);
            } catch (Exception e) {
                warnings.add("Failed to normalize " + fieldName + " path '" + rawPath + "' in module: " + safeModuleId(module));
            }
        }
    }

    private void normalizeClasspathList(
            Path repoRoot,
            List<String> rawPaths,
            Set<Path> target,
            WarningCollector warnings,
            String fieldName,
            BuildModuleManifest module
    ) {
        if (rawPaths == null) {
            return;
        }

        for (String rawPath : rawPaths) {
            if (rawPath == null || rawPath.isBlank()) {
                warnings.add("Blank " + fieldName + " entry found in module: " + safeModuleId(module));
                continue;
            }

            try {
                Path normalized = normalizePath(repoRoot, rawPath);
                if (!Files.exists(normalized)) {
                    warnings.add(fieldName + " entry does not exist: " + normalized);
                    continue;
                }
                target.add(normalized);
            } catch (Exception e) {
                warnings.add("Failed to normalize " + fieldName + " path '" + rawPath + "' in module: " + safeModuleId(module));
            }
        }
    }

    private Path normalizePath(Path repoRoot, String rawPath) {
        Path path = Path.of(rawPath);
        return path.isAbsolute() ? path.normalize() : repoRoot.resolve(path).normalize();
    }

    private List<BuildModuleManifest> safeModules(List<BuildModuleManifest> modules) {
        return modules == null ? List.of() : modules;
    }

    private String safeModuleId(BuildModuleManifest module) {
        if (module == null || module.getModuleId() == null || module.getModuleId().isBlank()) {
            return "<unknown-module>";
        }
        return module.getModuleId();
    }

    private record PathNormalizationResult(
            ExtractionPreflightResult.NormalizedExtractionContext context,
            List<String> warnings
    ) {
    }
}
