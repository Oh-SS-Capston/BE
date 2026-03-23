package com.example.ossdoc.domain.build.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildPathNormalizerTest {

    private final BuildPathNormalizer buildPathNormalizer = new BuildPathNormalizer();

    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativePathAgainstBaseDir() {
        String normalized = buildPathNormalizer.toAbsolutePath(tempDir, "src/main/java");

        assertEquals(
                buildPathNormalizer.normalize(tempDir.resolve("src/main/java")),
                normalized
        );
    }

    @Test
    void normalizesAbsolutePath() {
        Path raw = tempDir.resolve("repo").resolve("..").resolve("repo").resolve("build/classes/java/main");

        String normalized = buildPathNormalizer.normalize(raw);

        assertEquals(
                buildPathNormalizer.normalize(tempDir.resolve("repo/build/classes/java/main")),
                normalized
        );
    }
}
