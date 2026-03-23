package com.example.ossdoc.domain.build.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildDetectorTest {

    private final BuildDetector buildDetector = new BuildDetector();

    @TempDir
    Path tempDir;

    @Test
    void detectsBothGradleAndMavenWhenBuildFilesCoexist() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("gradlew.bat"), "@echo off");
        Files.writeString(tempDir.resolve("mvnw.cmd"), "@echo off");

        BuildDetector.Detected detected = buildDetector.detect(tempDir);

        assertTrue(detected.hasGradle());
        assertTrue(detected.hasMaven());
        assertTrue(detected.gradleWrapperExists());
        assertTrue(detected.mavenWrapperExists());
        assertTrue(detected.hasAnyBuildTool());
    }

    @Test
    void returnsNoBuildToolWhenNoBuildFiles() {
        BuildDetector.Detected detected = buildDetector.detect(tempDir);

        assertFalse(detected.hasGradle());
        assertFalse(detected.hasMaven());
        assertFalse(detected.hasAnyBuildTool());
    }
}
