package com.example.ossdoc.domain.build.service;

import com.example.ossdoc.domain.build.enums.BuildToolKind;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class BuildDetector {

    public Detected detect(Path repoRoot) {
        boolean hasGradle = Files.exists(repoRoot.resolve("settings.gradle"))
                || Files.exists(repoRoot.resolve("settings.gradle.kts"))
                || Files.exists(repoRoot.resolve("build.gradle"))
                || Files.exists(repoRoot.resolve("build.gradle.kts"));

        boolean hasMaven = Files.exists(repoRoot.resolve("pom.xml"));

        boolean gradleWrapper = Files.exists(repoRoot.resolve("gradlew")) || Files.exists(repoRoot.resolve("gradlew.bat"));
        boolean mavenWrapper = Files.exists(repoRoot.resolve("mvnw")) || Files.exists(repoRoot.resolve("mvnw.cmd"));

        if (hasGradle) return new Detected(BuildToolKind.GRADLE, gradleWrapper);
        if (hasMaven)  return new Detected(BuildToolKind.MAVEN, mavenWrapper);
        return new Detected(BuildToolKind.NONE, false);
    }

    public record Detected(BuildToolKind tool, boolean wrapperExists) {}
}
