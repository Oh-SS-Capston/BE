package com.example.ossdoc.domain.build.support;

import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class RepoRootResolver {

    public Path resolveActualRoot(Path repoRoot) {
        if (hasBuildFiles(repoRoot)) return repoRoot;

        List<Path> subDirs = listImmediateDirs(repoRoot);
        if (subDirs.size() == 1) {
            Path child = subDirs.get(0);
            if (hasBuildFiles(child)) return child;
        }
        return repoRoot;
    }

    private boolean hasBuildFiles(Path dir) {
        return Files.exists(dir.resolve("pom.xml"))
                || Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("build.gradle.kts"))
                || Files.exists(dir.resolve("settings.gradle"))
                || Files.exists(dir.resolve("settings.gradle.kts"));
    }

    private List<Path> listImmediateDirs(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> result = new ArrayList<>();
            s.filter(Files::isDirectory).forEach(result::add);
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
