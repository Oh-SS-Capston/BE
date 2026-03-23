package com.example.ossdoc.domain.extraction.service.support;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;

import java.nio.file.Path;
import java.util.List;

public record ExtractionPreflightResult(
        BuildManifest buildManifest,
        BuildOutputVerifier.BuildOutputVerificationResult buildVerification,
        BytecodeAvailabilityChecker.BytecodeAvailabilityResult bytecodeAvailability,
        ExtractionModeResolver.ExtractionModeResolutionResult modeResolution,
        NormalizedExtractionContext normalizedContext,
        List<String> warnings,
        List<String> blockingReasons,
        boolean canProceed
) {
    public ExtractionPreflightResult {
        normalizedContext = normalizedContext == null
                ? NormalizedExtractionContext.empty()
                : normalizedContext;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }

    public ExtractionMode resolvedMode() {
        return modeResolution == null ? null : modeResolution.resolvedMode();
    }

    public boolean bytecodeAvailable() {
        return bytecodeAvailability != null && bytecodeAvailability.hasUsableBytecode();
    }

    public boolean classpathReady() {
        return normalizedContext != null && normalizedContext.classpathReady();
    }

    public boolean hasBlockingReasons() {
        return !blockingReasons.isEmpty();
    }

    public boolean hasAnyUsableSourceInput() {
        return normalizedContext != null && normalizedContext.hasAnyJavaRoots();
    }

    public boolean hasAnyUsableBytecodeInput() {
        return normalizedContext != null && normalizedContext.hasAnyClassesDirs();
    }

    public record NormalizedExtractionContext(
            List<ModuleNormalizedContext> modules,
            List<Path> compileClasspath,
            List<Path> runtimeClasspath
    ) {
        public NormalizedExtractionContext {
            modules = modules == null ? List.of() : List.copyOf(modules);
            compileClasspath = compileClasspath == null ? List.of() : List.copyOf(compileClasspath);
            runtimeClasspath = runtimeClasspath == null ? List.of() : List.copyOf(runtimeClasspath);
        }

        public boolean hasAnyJavaRoots() {
            return modules.stream().anyMatch(ModuleNormalizedContext::hasAnyJavaRoots);
        }

        public boolean hasAnyClassesDirs() {
            return modules.stream().anyMatch(ModuleNormalizedContext::hasAnyClassesDirs);
        }

        public boolean classpathReady() {
            return !compileClasspath.isEmpty();
        }

        /**
         * 기존 평평한 루트 접근 방식이 필요할 때 사용
         */
        public List<Path> sourceRoots() {
            return modules.stream()
                    .flatMap(module -> module.sourceRoots().stream())
                    .toList();
        }

        public List<Path> testRoots() {
            return modules.stream()
                    .flatMap(module -> module.testRoots().stream())
                    .toList();
        }

        public List<Path> resourceRoots() {
            return modules.stream()
                    .flatMap(module -> module.resourceRoots().stream())
                    .toList();
        }

        public List<Path> classesDirs() {
            return modules.stream()
                    .flatMap(module -> module.classesDirs().stream())
                    .toList();
        }

        public static NormalizedExtractionContext empty() {
            return new NormalizedExtractionContext(
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    public record ModuleNormalizedContext(
            String moduleName,
            List<Path> sourceRoots,
            List<Path> testRoots,
            List<Path> resourceRoots,
            List<Path> classesDirs
    ) {
        public ModuleNormalizedContext {
            sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
            testRoots = testRoots == null ? List.of() : List.copyOf(testRoots);
            resourceRoots = resourceRoots == null ? List.of() : List.copyOf(resourceRoots);
            classesDirs = classesDirs == null ? List.of() : List.copyOf(classesDirs);
        }

        public boolean hasAnyJavaRoots() {
            return !sourceRoots.isEmpty() || !testRoots.isEmpty();
        }

        public boolean hasAnyClassesDirs() {
            return !classesDirs.isEmpty();
        }

        public static ModuleNormalizedContext empty(String moduleName) {
            return new ModuleNormalizedContext(
                    moduleName,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }
}