package com.example.ossdoc.domain.extraction.service.extractor;

import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExtractionRunCacheTest {

    @Test
    @DisplayName("같은 AST parser key는 같은 worker thread 안에서 ParserConfiguration을 재사용한다")
    void reusesParserConfigurationForSameKeyInSameThread() {
        ExtractionRunCache cache = new ExtractionRunCache();
        ExtractionRunCache.AstParserConfigurationKey key =
                ExtractionRunCache.AstParserConfigurationKey.from(
                        "root",
                        List.of(Path.of("src/main/java")),
                        List.of(Path.of("build/libs/app.jar"))
                );
        AtomicInteger buildCount = new AtomicInteger();

        ParserConfiguration first = cache.astParserConfiguration(key, ignored -> {
            buildCount.incrementAndGet();
            return new ParserConfiguration();
        });
        ParserConfiguration second = cache.astParserConfiguration(key, ignored -> {
            buildCount.incrementAndGet();
            return new ParserConfiguration();
        });

        assertSame(first, second);
        assertEquals(1, buildCount.get());
        assertEquals(1, cache.astParserConfigurationKeyCount());
    }

    @Test
    @DisplayName("source root나 classpath가 다르면 AST parser cache key를 분리한다")
    void separatesParserConfigurationByAstInputs() {
        ExtractionRunCache cache = new ExtractionRunCache();
        AtomicInteger buildCount = new AtomicInteger();

        ExtractionRunCache.AstParserConfigurationKey mainKey =
                ExtractionRunCache.AstParserConfigurationKey.from(
                        "root",
                        List.of(Path.of("src/main/java")),
                        List.of(Path.of("build/libs/app.jar"))
                );
        ExtractionRunCache.AstParserConfigurationKey testKey =
                ExtractionRunCache.AstParserConfigurationKey.from(
                        "root",
                        List.of(Path.of("src/test/java")),
                        List.of(Path.of("build/libs/app.jar"))
                );

        cache.astParserConfiguration(mainKey, ignored -> {
            buildCount.incrementAndGet();
            return new ParserConfiguration();
        });
        cache.astParserConfiguration(testKey, ignored -> {
            buildCount.incrementAndGet();
            return new ParserConfiguration();
        });

        assertEquals(2, buildCount.get());
        assertEquals(2, cache.astParserConfigurationKeyCount());
    }
}
