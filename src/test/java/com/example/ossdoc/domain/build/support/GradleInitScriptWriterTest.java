package com.example.ossdoc.domain.build.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleInitScriptWriterTest {

    private final GradleInitScriptWriter writer = new GradleInitScriptWriter();

    @TempDir
    Path tempDir;

    @Test
    void writesScriptThatDumpsAbsolutePaths() throws IOException {
        Path script = writer.write(tempDir);
        String content = Files.readString(script);

        assertTrue(content.contains("main.java.srcDirs.collect { it.absolutePath }"));
        assertTrue(content.contains("main.output.classesDirs.files.collect { it.absolutePath }"));
        assertTrue(content.contains("main.compileClasspath.files.collect { it.absolutePath }"));
    }
}
