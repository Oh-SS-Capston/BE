package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 컴파일러가 자동 생성하는 어노테이션(@kotlin.Metadata)은 evidence로 수집하지 않는다.
 *
 * 이 어노테이션은 리플렉션용 인코딩 문자열만 담고 있어 Semantic Graph에 기여하지 못하면서
 * facts.json 크기를 키우고, 값에 NUL이 섞여 artifact 저장까지 실패시킨 이력이 있다.
 * 스킵이 사라지면 이 테스트가 깨진다.
 */
class AsmCompilerGeneratedAnnotationSkipTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("@kotlin.Metadata는 evidence로 수집하지 않고 일반 어노테이션은 그대로 수집한다")
    void skipsCompilerGeneratedAnnotationOnly() throws Exception {
        Path classes = compileFixture();

        ExtractionSink sink = extract(
                classes,
                classes.resolve("sample/KotlinLikeFixture.class")
        );

        Set<String> annotationNames = sink.toExtractedFacts()
                .evidence()
                .values()
                .stream()
                .map(EvidenceFact::attrs)
                .filter(attrs -> attrs != null && attrs.get("annotation_name") != null)
                .map(attrs -> String.valueOf(attrs.get("annotation_name")))
                .collect(Collectors.toSet());

        assertFalse(
                annotationNames.contains("kotlin.Metadata"),
                "컴파일러 생성 어노테이션이 evidence로 남으면 안 된다. 수집된 것: " + annotationNames
        );

        // 스킵이 과하게 동작해 일반 어노테이션까지 사라지면 안 된다.
        assertTrue(
                annotationNames.contains("sample.Marker"),
                "일반 어노테이션은 계속 수집돼야 한다. 수집된 것: " + annotationNames
        );
    }

    private Path compileFixture() throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path classes = tempDir.resolve("classes");

        Path metadataSource = sourceRoot.resolve("kotlin/Metadata.java");
        Path fixtureSource = sourceRoot.resolve("sample/KotlinLikeFixture.java");

        Files.createDirectories(metadataSource.getParent());
        Files.createDirectories(fixtureSource.getParent());
        Files.createDirectories(classes);

        // Kotlin 컴파일러가 붙이는 것과 같은 좌표(kotlin.Metadata)를 재현한다.
        Files.writeString(
                metadataSource,
                """
                package kotlin;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Metadata {
                    String[] d1() default {};
                }
                """,
                StandardCharsets.UTF_8
        );

        Files.writeString(
                fixtureSource,
                """
                package sample;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                @interface Marker {
                }

                @kotlin.Metadata(d1 = {"encoded-metadata-payload"})
                @Marker
                public class KotlinLikeFixture {
                }
                """,
                StandardCharsets.UTF_8
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK JavaCompiler가 필요함");

        int exitCode = compiler.run(
                null,
                null,
                null,
                "-g",
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString(),
                metadataSource.toString(),
                fixtureSource.toString()
        );

        assertEquals(0, exitCode, "fixture 컴파일 실패");
        return classes;
    }

    private ExtractionSink extract(Path classes, Path classFile) throws Exception {
        ExtractionContext context = mock(ExtractionContext.class);

        when(context.repoRoot()).thenReturn(tempDir);
        when(context.module()).thenReturn("sample-app");
        when(context.bytecodeRootString()).thenReturn(classes.toString());
        when(context.includeObservations()).thenReturn(true);

        ExtractionSink sink = new ExtractionSink();

        Method visitClassFile = AsmBytecodeFactsExtractor.class
                .getDeclaredMethod(
                        "visitClassFile",
                        ExtractionContext.class,
                        Path.class,
                        ExtractionSink.class
                );

        visitClassFile.setAccessible(true);
        visitClassFile.invoke(
                new AsmBytecodeFactsExtractor(),
                context,
                classFile,
                sink
        );

        return sink;
    }
}
