package com.example.ossdoc.domain.extraction.service.extractor;

import com.github.javaparser.ParserConfiguration;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 하나의 facts extraction 실행 동안 chunk worker들이 공유하는 성능 캐시.
 *
 * 전역 캐시가 아니기 때문에 다른 repo/run의 classpath가 섞이지 않고,
 * 실행이 끝나면 facade의 지역 변수와 함께 GC 대상이 된다.
 */
public class ExtractionRunCache {

    private final ConcurrentMap<AstParserConfigurationKey, ThreadLocal<ParserConfiguration>> astParserConfigurations =
            new ConcurrentHashMap<>();

    ParserConfiguration astParserConfiguration(
            AstParserConfigurationKey key,
            Function<AstParserConfigurationKey, ParserConfiguration> builder
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(builder, "builder must not be null");

        // JavaParser Symbol Solver는 내부 캐시를 가질 수 있어 worker thread별로 재사용한다.
        // chunk마다 새로 만들던 source root/JAR solver 초기화 비용은 줄이고,
        // 서로 다른 thread가 같은 solver 인스턴스를 동시에 만지는 위험은 피한다.
        return astParserConfigurations
                .computeIfAbsent(key, cacheKey -> ThreadLocal.withInitial(() -> builder.apply(cacheKey)))
                .get();
    }

    int astParserConfigurationKeyCount() {
        return astParserConfigurations.size();
    }

    record AstParserConfigurationKey(
            String module,
            List<Path> astLookupRoots,
            List<Path> classpathEntries
    ) {

        AstParserConfigurationKey {
            module = module == null || module.isBlank() ? "root" : module;
            astLookupRoots = normalize(astLookupRoots);
            classpathEntries = normalize(classpathEntries);
        }

        static AstParserConfigurationKey from(
                String module,
                List<Path> astLookupRoots,
                List<Path> classpathEntries
        ) {
            return new AstParserConfigurationKey(module, astLookupRoots, classpathEntries);
        }

        private static List<Path> normalize(List<Path> paths) {
            if (paths == null || paths.isEmpty()) {
                return List.of();
            }
            return paths.stream()
                    .filter(Objects::nonNull)
                    .map(Path::normalize)
                    .distinct()
                    .toList();
        }
    }
}
