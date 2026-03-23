package com.example.ossdoc.domain.extraction.service.support;

import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.dto.model.ChunkingPolicy;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class ChunkPlanner {

    public List<ChunkDescriptor> plan(
            FactsExtractRequest request,
            ExtractionPreflightResult preflightResult,
            Path repoRoot,
            ChunkingPolicy policy
    ) {
        Objects.requireNonNull(preflightResult, "preflightResult must not be null");
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");

        ChunkingPolicy effectivePolicy = policy == null ? ChunkingPolicy.standard() : policy;
        IncludeGlobMatcher includeMatcher = new IncludeGlobMatcher(request == null ? List.of() : request.includeGlobs());
        IncludeGlobMatcher excludeMatcher = new IncludeGlobMatcher(request == null ? List.of() : request.excludeGlobs());

        List<ChunkDescriptor> chunks = new ArrayList<>();

        for (ExtractionPreflightResult.ModuleNormalizedContext module : preflightResult.normalizedContext().modules()) {
            if (!isTargetModule(request, module.moduleName())) {
                continue;
            }

            chunks.addAll(planAstChunks(request, repoRoot, effectivePolicy, includeMatcher, excludeMatcher, module));

            if (supportsAsm(preflightResult)) {
                chunks.addAll(planAsmChunks(repoRoot, effectivePolicy, includeMatcher, excludeMatcher, module));
            }
        }

        return List.copyOf(chunks);
    }

    private boolean supportsAsm(ExtractionPreflightResult preflightResult) {
        ExtractionMode mode = preflightResult.resolvedMode();
        return mode == ExtractionMode.AST_PLUS_BYTECODE || mode == ExtractionMode.AST_PLUS_PARTIAL_BYTECODE;
    }

    private boolean isTargetModule(FactsExtractRequest request, String moduleName) {
        if (request == null || request.targetModules() == null || request.targetModules().isEmpty()) {
            return true;
        }
        return request.targetModules().stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(moduleName::equals);
    }

    private List<ChunkDescriptor> planAstChunks(
            FactsExtractRequest request,
            Path repoRoot,
            ChunkingPolicy policy,
            IncludeGlobMatcher includeMatcher,
            IncludeGlobMatcher excludeMatcher,
            ExtractionPreflightResult.ModuleNormalizedContext module
    ) {
        List<ChunkDescriptor> chunks = new ArrayList<>();
        chunks.addAll(planChunksForRoots(
                repoRoot,
                module.moduleName(),
                module.sourceRoots(),
                ChunkKind.AST,
                ".java",
                policy.astMaxFiles(),
                policy.astMaxBytes(),
                includeMatcher,
                excludeMatcher
        ));

        if (request != null && request.includeTests()) {
            chunks.addAll(planChunksForRoots(
                    repoRoot,
                    module.moduleName(),
                    module.testRoots(),
                    ChunkKind.AST,
                    ".java",
                    policy.astMaxFiles(),
                    policy.astMaxBytes(),
                    includeMatcher,
                    excludeMatcher
            ));
        }
        return chunks;
    }

    private List<ChunkDescriptor> planAsmChunks(
            Path repoRoot,
            ChunkingPolicy policy,
            IncludeGlobMatcher includeMatcher,
            IncludeGlobMatcher excludeMatcher,
            ExtractionPreflightResult.ModuleNormalizedContext module
    ) {
        return planChunksForRoots(
                repoRoot,
                module.moduleName(),
                module.classesDirs(),
                ChunkKind.ASM,
                ".class",
                policy.asmMaxFiles(),
                policy.asmMaxBytes(),
                includeMatcher,
                excludeMatcher
        );
    }

    private List<ChunkDescriptor> planChunksForRoots(
            Path repoRoot,
            String moduleName,
            List<Path> roots,
            ChunkKind kind,
            String fileSuffix,
            int maxFiles,
            long maxBytes,
            IncludeGlobMatcher includeMatcher,
            IncludeGlobMatcher excludeMatcher
    ) {
        List<ChunkDescriptor> chunks = new ArrayList<>();
        if (roots == null || roots.isEmpty()) {
            return chunks;
        }

        for (Path root : roots) {
            List<Path> files = collectFiles(root, fileSuffix);
            if (files.isEmpty()) {
                continue;
            }

            List<FileEntry> accepted = new ArrayList<>();
            for (Path file : files) {
                String repoRelative = RepoPathUtils.toRepoRelative(repoRoot, file);
                if (!includeMatcher.matches(repoRelative)) {
                    continue;
                }
                if (excludeMatcher.hasRules() && excludeMatcher.matches(repoRelative)) {
                    continue;
                }

                try {
                    long size = Files.size(file);
                    accepted.add(new FileEntry(file, repoRelative, size));
                } catch (IOException e) {
                    // 파일 크기 계산 실패 시 skip
                }
            }

            if (accepted.isEmpty()) {
                continue;
            }

            accepted.sort(Comparator.comparing(FileEntry::repoRelative));
            chunks.addAll(sliceRootIntoChunks(repoRoot, moduleName, root, kind, accepted, maxFiles, maxBytes));
        }

        return chunks;
    }

    private List<ChunkDescriptor> sliceRootIntoChunks(
            Path repoRoot,
            String moduleName,
            Path root,
            ChunkKind kind,
            List<FileEntry> accepted,
            int maxFiles,
            long maxBytes
    ) {
        List<ChunkDescriptor> chunks = new ArrayList<>();
        List<String> currentFiles = new ArrayList<>();
        long currentBytes = 0L;
        int chunkIndex = 0;

        for (FileEntry entry : accepted) {
            boolean shouldRotate = !currentFiles.isEmpty()
                    && (currentFiles.size() >= maxFiles || currentBytes + entry.size() > maxBytes);

            if (shouldRotate) {
                chunks.add(buildChunk(repoRoot, moduleName, root, kind, chunkIndex++, currentFiles, currentBytes));
                currentFiles = new ArrayList<>();
                currentBytes = 0L;
            }

            currentFiles.add(entry.repoRelative());
            currentBytes += entry.size();
        }

        if (!currentFiles.isEmpty()) {
            chunks.add(buildChunk(repoRoot, moduleName, root, kind, chunkIndex, currentFiles, currentBytes));
        }

        return chunks;
    }

    private ChunkDescriptor buildChunk(
            Path repoRoot,
            String moduleName,
            Path root,
            ChunkKind kind,
            int chunkIndex,
            List<String> files,
            long totalBytes
    ) {
        String rootPath = RepoPathUtils.toRepoRelative(repoRoot, root);
        String key = String.join("|",
                kind.code(),
                moduleName,
                rootPath,
                String.valueOf(chunkIndex),
                String.join(",", files)
        );

        String chunkId = "chunk_" + HashSupport.sha256Hex(key).substring(0, 16);

        return ChunkDescriptor.builder()
                .chunkId(chunkId)
                .kind(kind)
                .module(moduleName)
                .rootPath(rootPath)
                .files(List.copyOf(files))
                .totalBytes(totalBytes)
                .fileCount(files.size())
                .build();
    }

    private List<Path> collectFiles(Path root, String suffix) {
        if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private record FileEntry(Path path, String repoRelative, long size) {
    }
}
