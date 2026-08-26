package com.example.ossdoc.domain.build.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * configuration cache가 켜진 저장소에서 dump가 실패하지 않으려면
     * 태스크 실행 시점 코드가 Project(prj)를 참조하면 안 된다.
     * 구조가 doLast 방식으로 되돌아가면 이 테스트가 깨진다.
     */
    @Test
    void writesConfigurationCacheSafeScript() throws IOException {
        Path script = writer.write(tempDir);
        String content = Files.readString(script);

        // 모델 수집은 설정 시점(projectsEvaluated)에 끝내고, 태스크는 문자열만 들고 있어야 한다.
        assertTrue(content.contains("gradle.projectsEvaluated"));
        assertTrue(content.contains("abstract Property<String> getPayload()"));
        assertTrue(content.contains("prj.tasks.register(\"ossdocDump\", OssdocDumpTask)"));

        // 실행 시점에 Project를 붙잡는 doLast 구조가 남아 있으면 안 된다.
        assertFalse(content.contains("doLast"));
    }

    /**
     * -I init script는 composite build의 모든 빌드에 적용되므로,
     * includeBuild로 끌어온 build-logic 빌드에서는 덤프를 건너뛰어야 한다.
     * 이 가드가 사라지면 project substitution으로 의존성을 해소하는 저장소(JUnit 등)에서
     * "Could not find <group>:<artifact>:" 로 dump 전체가 실패한다.
     */
    @Test
    void writesScriptThatSkipsIncludedBuilds() throws IOException {
        Path script = writer.write(tempDir);
        String content = Files.readString(script);

        assertTrue(content.contains("if (g.parent != null)"));
    }
}
