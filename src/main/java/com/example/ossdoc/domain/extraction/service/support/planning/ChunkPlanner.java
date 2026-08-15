package com.example.ossdoc.domain.extraction.service.support.planning;
import com.example.ossdoc.domain.extraction.service.support.util.HashSupport;
import com.example.ossdoc.domain.extraction.service.support.util.RepoPathUtils;
import com.example.ossdoc.domain.extraction.service.support.preflight.BytecodeAvailabilityChecker;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightResult;

import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.dto.model.ChunkingPolicy;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                chunks.addAll(planAsmChunks(preflightResult, repoRoot, effectivePolicy, includeMatcher, excludeMatcher, module));
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
            ExtractionPreflightResult preflightResult,
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
                excludeMatcher,
                preflightClassFilesByRoot(preflightResult)
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
        return planChunksForRoots(
                repoRoot,
                moduleName,
                roots,
                kind,
                fileSuffix,
                maxFiles,
                maxBytes,
                includeMatcher,
                excludeMatcher,
                Map.of()
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
            IncludeGlobMatcher excludeMatcher,
            Map<Path, List<Path>> precollectedFilesByRoot
    ) {
        List<ChunkDescriptor> chunks = new ArrayList<>();
        if (roots == null || roots.isEmpty()) {
            return chunks;
        }

        for (Path root : roots) {
            List<FileEntry> files = collectOrReuseFileEntries(repoRoot, root, fileSuffix, precollectedFilesByRoot);
            if (files.isEmpty()) {
                continue;
            }

            List<FileEntry> accepted = new ArrayList<>();
            for (FileEntry entry : files) {
                String repoRelative = entry.repoRelative();
                if (!includeMatcher.matches(repoRelative)) {
                    continue;
                }
                if (excludeMatcher.hasRules() && excludeMatcher.matches(repoRelative)) {
                    continue;
                }

                accepted.add(entry);
            }

            if (accepted.isEmpty()) {
                continue;
            }

            accepted.sort(Comparator.comparing(FileEntry::repoRelative));
            chunks.addAll(sliceRootIntoChunks(repoRoot, moduleName, root, kind, accepted, maxFiles, maxBytes));
        }

        return chunks;
    }

    private Map<Path, List<Path>> preflightClassFilesByRoot(ExtractionPreflightResult preflightResult) {
        BytecodeAvailabilityChecker.BytecodeAvailabilityResult bytecodeAvailability =
                preflightResult == null ? null : preflightResult.bytecodeAvailability();
        if (bytecodeAvailability == null) {
            return Map.of();
        }
        return bytecodeAvailability.classFilesByRoot();
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

    private List<FileEntry> collectFileEntries(Path repoRoot, Path root, String suffix) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }

        List<FileEntry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.toString().endsWith(suffix)) {
                        // 파일 방문 시점의 size metadata를 함께 저장해
                        // 청크 생성 단계에서 Files.size(file)을 반복 호출하지 않는다.
                        entries.add(new FileEntry(
                                file,
                                RepoPathUtils.toRepoRelative(repoRoot, file),
                                attrs.size()
                        ));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return List.of();
        }

        entries.sort(Comparator.comparing(FileEntry::repoRelative));
        return entries;
    }

    private List<FileEntry> collectOrReuseFileEntries(
            Path repoRoot,
            Path root,
            String suffix,
            Map<Path, List<Path>> precollectedFilesByRoot
    ) {
        if (root == null) {
            return List.of();
        }

        if (precollectedFilesByRoot != null) {
            List<Path> precollected = precollectedFilesByRoot.get(root);
            if (precollected == null) {
                precollected = precollectedFilesByRoot.get(root.normalize());
            }
            if (precollected != null) {
                return toFileEntries(repoRoot, precollected);
            }
        }

        return collectFileEntries(repoRoot, root, suffix);
    }

    private List<FileEntry> toFileEntries(Path repoRoot, List<Path> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        List<FileEntry> entries = new ArrayList<>();
        for (Path file : files) {
            try {
                entries.add(new FileEntry(
                        file,
                        RepoPathUtils.toRepoRelative(repoRoot, file),
                        Files.size(file)
                ));
            } catch (IOException e) {
                // preflight 이후 파일이 삭제/잠김 상태가 될 수 있으므로 전체 계획은 중단하지 않고 해당 파일만 제외한다.
            }
        }
        entries.sort(Comparator.comparing(FileEntry::repoRelative));
        return entries;
    }

    private record FileEntry(Path path, String repoRelative, long size) {
    }
}
