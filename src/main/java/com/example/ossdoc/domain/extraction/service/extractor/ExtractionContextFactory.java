package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.service.support.ExtractionPreflightResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class ExtractionContextFactory {

    public ExtractionContext create(
            FactsExtractRequest request,
            ExtractionPreflightResult preflightResult,
            Path repoRoot,
            ChunkDescriptor chunk
    ) {
        Objects.requireNonNull(preflightResult, "preflightResult must not be null");
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");

        Path normalizedRepoRoot = repoRoot.normalize();
        Path rootPath = resolve(normalizedRepoRoot, chunk.rootPath());
        List<Path> files = chunk.files().stream()
                .map(file -> resolve(normalizedRepoRoot, file))
                .toList();

        ExtractionPreflightResult.ModuleNormalizedContext moduleContext = findModuleContext(preflightResult, chunk.module());
        List<Path> astLookupRoots = astLookupRoots(request, moduleContext, chunk, rootPath);

        return ExtractionContext.builder()
                .repoRoot(normalizedRepoRoot)
                .chunk(chunk)
                .module(chunk.module())
                .rootPath(rootPath)
                .files(files)
                .astLookupRoots(astLookupRoots)
                .compileClasspathEntries(preflightResult.normalizedContext().compileClasspath())
                .runtimeClasspathEntries(preflightResult.normalizedContext().runtimeClasspath())
                .includeObservations(request != null && request.includeObservations())
                .build();
    }

    private ExtractionPreflightResult.ModuleNormalizedContext findModuleContext(
            ExtractionPreflightResult preflightResult,
            String moduleName
    ) {
        return preflightResult.normalizedContext().modules().stream()
                .filter(module -> Objects.equals(module.moduleName(), moduleName))
                .findFirst()
                .orElse(null);
    }

    private List<Path> astLookupRoots(
            FactsExtractRequest request,
            ExtractionPreflightResult.ModuleNormalizedContext moduleContext,
            ChunkDescriptor chunk,
            Path fallbackRoot
    ) {
        if (chunk.kind() != com.example.ossdoc.domain.extraction.enums.ChunkKind.AST) {
            return List.of();
        }

        if (moduleContext == null) {
            return List.of(fallbackRoot);
        }

        List<Path> lookupRoots = new ArrayList<>(moduleContext.sourceRoots());
        if (request != null && request.includeTests()) {
            lookupRoots.addAll(moduleContext.testRoots());
        }

        if (lookupRoots.isEmpty()) {
            lookupRoots.add(fallbackRoot);
        }
        return List.copyOf(lookupRoots);
    }

    private Path resolve(Path repoRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("rawPath must not be blank");
        }

        Path path = Path.of(rawPath);
        return path.isAbsolute() ? path.normalize() : repoRoot.resolve(path).normalize();
    }
}
