package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceOnlyModuleScannerTest {

    private final SourceOnlyModuleScanner scanner =
            new SourceOnlyModuleScanner(new BuildPathNormalizer());

    @TempDir
    Path tempDir;

    @Test
    void returnsNormalizedAbsolutePathsForDiscoveredRoots() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("module-a/src/test/java"));

        List<BuildModuleManifest> modules = scanner.scan(tempDir);

        assertEquals(2, modules.size());
        assertTrue(modules.stream()
                .flatMap(module -> module.getSourceRoots().stream())
                .allMatch(path -> Path.of(path).isAbsolute()));
        assertTrue(modules.stream()
                .flatMap(module -> module.getTestRoots().stream())
                .allMatch(path -> Path.of(path).isAbsolute()));
    }
}
