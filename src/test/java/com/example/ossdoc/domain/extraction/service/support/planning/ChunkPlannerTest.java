package com.example.ossdoc.domain.extraction.service.support.planning;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.dto.model.ChunkingPolicy;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.service.support.preflight.BytecodeAvailabilityChecker;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionModeResolver;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPlannerTest {

    @TempDir
    Path repoRoot;

    @Test
    void bytecodeAvailabilityCheckerExposesScannedClassFilesByRoot() throws IOException {
        Path classesRoot = repoRoot.resolve("build/classes/java/main");
        Path classFile = classesRoot.resolve("sample/Cached.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[]{0, 1, 2});
        Files.writeString(classesRoot.resolve("sample/ignored.txt"), "ignored");

        BytecodeAvailabilityChecker.BytecodeAvailabilityResult result =
                new BytecodeAvailabilityChecker().check(buildManifest(), repoRoot);

        assertTrue(result.hasUsableBytecode());
        assertEquals(1L, result.classFileCount());
        assertEquals(List.of(classFile), result.classFilesByRoot().get(classesRoot));
    }

    @Test
    void planAsmChunksUsesPreflightClassFileListBeforeWalkingRoot() throws IOException {
        Path classesRoot = repoRoot.resolve("build/classes/java/main");
        Path cachedClass = classesRoot.resolve("sample/Cached.class");
        Path unplannedClass = classesRoot.resolve("sample/Unplanned.class");
        Files.createDirectories(cachedClass.getParent());
        Files.write(cachedClass, new byte[]{0});
        Files.write(unplannedClass, new byte[]{0});

        ChunkPlanner planner = new ChunkPlanner();
        List<ChunkDescriptor> chunks = planner.plan(
                FactsExtractRequest.builder()
                        .runId("run")
                        .mode(ExtractionMode.AST_PLUS_BYTECODE)
                        .build(),
                preflightResult(classesRoot, cachedClass),
                repoRoot,
                new ChunkingPolicy(10, 1_000L, 10, 1_000L, 1)
        );

        List<ChunkDescriptor> asmChunks = chunks.stream()
                .filter(chunk -> chunk.kind() == ChunkKind.ASM)
                .toList();

        assertEquals(1, asmChunks.size());
        assertEquals(List.of("build/classes/java/main/sample/Cached.class"), asmChunks.get(0).files());
    }

    private BuildManifest buildManifest() {
        return BuildManifest.builder()
                .buildMode(BuildMode.FULL)
                .modules(List.of(BuildModuleManifest.builder()
                        .moduleId(":")
                        .sourceRoots(List.of())
                        .testRoots(List.of())
                        .resourceRoots(List.of())
                        .classesDirs(List.of("build/classes/java/main"))
                        .compileClasspath(List.of())
                        .runtimeClasspath(List.of())
                        .build()))
                .build();
    }

    private ExtractionPreflightResult preflightResult(Path classesRoot, Path classFile) {
        BytecodeAvailabilityChecker.BytecodeAvailabilityResult bytecodeAvailability =
                new BytecodeAvailabilityChecker.BytecodeAvailabilityResult(
                        true,
                        false,
                        1,
                        1,
                        List.of(classesRoot),
                        List.of(),
                        Map.of(classesRoot, List.of(classFile)),
                        1L,
                        List.of()
                );

        ExtractionModeResolver.ExtractionModeResolutionResult modeResolution =
                new ExtractionModeResolver.ExtractionModeResolutionResult(
                        ExtractionMode.AST_PLUS_BYTECODE,
                        ExtractionMode.AST_PLUS_BYTECODE,
                        ExtractionMode.AST_PLUS_BYTECODE,
                        false,
                        null,
                        List.of()
                );

        ExtractionPreflightResult.NormalizedExtractionContext normalizedContext =
                new ExtractionPreflightResult.NormalizedExtractionContext(
                        List.of(new ExtractionPreflightResult.ModuleNormalizedContext(
                                ":",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(classesRoot)
                        )),
                        List.of(),
                        List.of()
                );

        return new ExtractionPreflightResult(
                null,
                null,
                bytecodeAvailability,
                modeResolution,
                normalizedContext,
                List.of(),
                List.of(),
                true
        );
    }
}
