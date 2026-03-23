package com.example.ossdoc.domain.extraction.service.support;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.extraction.enums.BytecodeAvailability;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class BytecodeAvailabilityChecker {

    public BytecodeAvailabilityResult check(BuildManifest buildManifest, Path repoRoot) {
        List<String> warnings = new ArrayList<>();

        if (buildManifest == null) {
            warnings.add("build_manifest is null");
            return BytecodeAvailabilityResult.empty(warnings);
        }

        if (repoRoot == null) {
            warnings.add("repoRoot is null");
            return BytecodeAvailabilityResult.empty(warnings);
        }

        Set<Path> declaredRoots = new LinkedHashSet<>();
        Set<Path> existingRoots = new LinkedHashSet<>();
        Set<Path> missingRoots = new LinkedHashSet<>();

        long classFileCount = 0L;

        for (BuildModuleManifest module : safeModules(buildManifest.getModules())) {
            for (String rawRoot : safeStrings(module.getClassesDirs())) {
                if (rawRoot == null || rawRoot.isBlank()) {
                    warnings.add("Blank classesDirs entry found in module: " + safeModuleId(module));
                    continue;
                }

                Path resolvedRoot;
                try {
                    resolvedRoot = normalizePath(repoRoot, rawRoot);
                } catch (Exception e) {
                    warnings.add("Invalid classesDirs path '" + rawRoot + "' in module: " + safeModuleId(module));
                    continue;
                }

                declaredRoots.add(resolvedRoot);

                if (!Files.exists(resolvedRoot) || !Files.isDirectory(resolvedRoot)) {
                    missingRoots.add(resolvedRoot);
                    warnings.add("classesDirs path does not exist: " + resolvedRoot);
                    continue;
                }

                existingRoots.add(resolvedRoot);

                try {
                    long count = countClassFiles(resolvedRoot);
                    if (count == 0L) {
                        warnings.add("classesDirs exists but contains no .class files: " + resolvedRoot);
                    } else {
                        classFileCount += count;
                    }
                } catch (IOException e) {
                    warnings.add("Failed to scan classesDirs: " + resolvedRoot + " (" + e.getMessage() + ")");
                    existingRoots.remove(resolvedRoot);
                    missingRoots.add(resolvedRoot);
                }
            }
        }

        int declaredRootCount = declaredRoots.size();
        int existingRootCount = existingRoots.size();

        boolean hasUsableBytecode = classFileCount > 0L;
        boolean partialAvailability =
                hasUsableBytecode && declaredRootCount > 0 && existingRootCount < declaredRootCount;

        return new BytecodeAvailabilityResult(
                hasUsableBytecode,
                partialAvailability,
                declaredRootCount,
                existingRootCount,
                List.copyOf(existingRoots),
                List.copyOf(missingRoots),
                classFileCount,
                List.copyOf(warnings)
        );
    }

    private long countClassFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .count();
        }
    }

    private Path normalizePath(Path repoRoot, String rawPath) {
        Path path = Path.of(rawPath);
        return path.isAbsolute() ? path.normalize() : repoRoot.resolve(path).normalize();
    }

    private List<BuildModuleManifest> safeModules(List<BuildModuleManifest> modules) {
        return modules == null ? List.of() : modules;
    }

    private List<String> safeStrings(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safeModuleId(BuildModuleManifest module) {
        if (module == null || module.getModuleId() == null || module.getModuleId().isBlank()) {
            return "<unknown-module>";
        }
        return module.getModuleId();
    }

    public record BytecodeAvailabilityResult(
            boolean hasUsableBytecode,
            boolean partialAvailability,
            int declaredRootCount,
            int existingRootCount,
            List<Path> existingRoots,
            List<Path> missingRoots,
            long classFileCount,
            List<String> warnings
    ) {
        public BytecodeAvailabilityResult {
            existingRoots = existingRoots == null ? List.of() : List.copyOf(existingRoots);
            missingRoots = missingRoots == null ? List.of() : List.copyOf(missingRoots);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public BytecodeAvailability availability() {
            if (!hasUsableBytecode) {
                return BytecodeAvailability.NONE;
            }
            return partialAvailability ? BytecodeAvailability.PARTIAL : BytecodeAvailability.FULL;
        }

        public boolean hasDeclaredRoots() {
            return declaredRootCount > 0;
        }

        public static BytecodeAvailabilityResult empty(List<String> warnings) {
            return new BytecodeAvailabilityResult(
                    false,
                    false,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    0L,
                    warnings
            );
        }
    }
}
