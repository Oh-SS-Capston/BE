package com.example.ossdoc.domain.extraction.service.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * facts 산출물 경로 계산 유틸
 */
public final class FactsPathResolver {

    private FactsPathResolver() {
    }

    public static Path ensureArtifactsRoot(Path artifactsRoot) throws IOException {
        Objects.requireNonNull(artifactsRoot, "artifactsRoot must not be null");
        Files.createDirectories(artifactsRoot);
        return artifactsRoot.normalize();
    }

    public static Path resolveFactsPath(Path artifactsRoot) {
        Objects.requireNonNull(artifactsRoot, "artifactsRoot must not be null");
        return artifactsRoot.resolve(FactsSchema.FACTS_FILE_NAME).normalize();
    }

    public static Path resolveFactsSha256Path(Path artifactsRoot) {
        Objects.requireNonNull(artifactsRoot, "artifactsRoot must not be null");
        return artifactsRoot.resolve(FactsSchema.FACTS_SHA256_FILE_NAME).normalize();
    }
}