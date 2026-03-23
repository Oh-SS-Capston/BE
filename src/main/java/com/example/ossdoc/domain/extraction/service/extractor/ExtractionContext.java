package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.service.support.RepoPathUtils;
import lombok.Builder;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * chunk 단위 extractor 입력 컨텍스트.
 *
 * 전제:
 * - repoRoot / rootPath / files / classpath는 preflight + chunk planner에서 이미 정규화된 값이다.
 * - extractor는 include/exclude glob으로 다시 전체 디렉터리를 순회하지 않고,
 *   planner가 확정한 files 목록만 처리한다.
 */
@Builder
public record ExtractionContext(
        Path repoRoot,
        ChunkDescriptor chunk,
        String module,
        Path rootPath,
        List<Path> files,
        List<Path> astLookupRoots,
        List<Path> compileClasspathEntries,
        List<Path> runtimeClasspathEntries,
        boolean includeObservations
) {

    public ExtractionContext {
        repoRoot = Objects.requireNonNull(repoRoot, "repoRoot must not be null").normalize();
        chunk = Objects.requireNonNull(chunk, "chunk must not be null");
        module = normalizeModule(module == null || module.isBlank() ? chunk.module() : module);
        rootPath = Objects.requireNonNull(rootPath, "rootPath must not be null").normalize();
        files = sanitizePaths(files);
        astLookupRoots = sanitizePaths(astLookupRoots);
        compileClasspathEntries = sanitizePaths(compileClasspathEntries);
        runtimeClasspathEntries = sanitizePaths(runtimeClasspathEntries);
    }

    public ChunkKind chunkKind() {
        return chunk.kind();
    }

    public String chunkId() {
        return chunk.chunkId();
    }

    public boolean isAstChunk() {
        return chunkKind() == ChunkKind.AST;
    }

    public boolean isAsmChunk() {
        return chunkKind() == ChunkKind.ASM;
    }

    public boolean hasFiles() {
        return !files.isEmpty();
    }

    public List<Path> classpathEntries() {
        return compileClasspathEntries;
    }

    public String rootPathString() {
        return RepoPathUtils.toRepoRelative(repoRoot, rootPath);
    }

    public String sourceRootString() {
        return isAstChunk() ? rootPathString() : null;
    }

    public String bytecodeRootString() {
        return isAsmChunk() ? rootPathString() : null;
    }

    private static String normalizeModule(String module) {
        if (module == null || module.isBlank()) {
            return "root";
        }
        return module;
    }

    private static List<Path> sanitizePaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }

        Set<Path> normalized = new LinkedHashSet<>();
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            normalized.add(path.normalize());
        }
        return List.copyOf(normalized);
    }
}
