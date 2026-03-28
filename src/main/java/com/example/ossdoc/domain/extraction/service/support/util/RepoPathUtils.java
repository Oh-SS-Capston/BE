package com.example.ossdoc.domain.extraction.service.support.util;

import java.nio.file.Path;
import java.util.Objects;

/**
 * repo 상대경로/경로 문자열 정규화 유틸
 */
public final class RepoPathUtils {

    private RepoPathUtils() {
    }

    public static String toRepoRelative(Path repoRoot, Path target) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(target, "target must not be null");

        Path normalizedRoot = repoRoot.normalize();
        Path normalizedTarget = target.normalize();

        String value = normalizedTarget.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedTarget).toString()
                : normalizedTarget.toString();

        return normalizeSeparators(value);
    }

    public static String normalizeSeparators(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        return path.replace('\\', '/');
    }

    public static String fileName(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}