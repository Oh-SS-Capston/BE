package com.example.ossdoc.domain.build.support;

import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class BuildPathNormalizer {

    public List<String> toAbsolutePaths(Path baseDir, List<String> rawPaths) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String rawPath : rawPaths) {
            String resolved = toAbsolutePath(baseDir, rawPath);
            if (resolved != null) {
                normalized.add(resolved);
            }
        }
        return normalized;
    }

    public String toAbsolutePath(Path baseDir, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }

        try {
            Path path = Path.of(rawPath.trim());
            Path resolved = path.isAbsolute() ? path : baseDir.resolve(path);
            return normalize(resolved);
        } catch (InvalidPathException e) {
            return rawPath.trim().replace("\\", "/");
        }
    }

    public String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "/");
    }
}
