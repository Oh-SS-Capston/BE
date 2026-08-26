package com.example.ossdoc.domain.build.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 역할:
 * 저장소 루트를 검사해 사용 가능한 빌드 도구(Gradle/Maven)를 감지한다.
 *
 * 책임:
 * 1) 빌드 파일 존재 여부 판단
 * 2) wrapper 존재 여부 판단
 * 3) 감지 결과를 Detected 레코드로 반환
 */
@Component
public class BuildDetector {

    /**
     * 역할:
     * 저장소의 빌드 도구/wrapper 존재 상태를 계산한다.
     *
     * 책임:
     * settings/build/pom/mvnw/gradlew 파일 존재 여부를 조합해 실행 분기 근거를 제공한다.
     */
    public Detected detect(Path repoRoot) {
        // Gradle 관련 빌드 파일 존재 여부
        boolean hasGradle = Files.exists(repoRoot.resolve("settings.gradle"))
                || Files.exists(repoRoot.resolve("settings.gradle.kts"))
                || Files.exists(repoRoot.resolve("build.gradle"))
                || Files.exists(repoRoot.resolve("build.gradle.kts"));

        // Maven 관련 빌드 파일 존재 여부
        boolean hasMaven = Files.exists(repoRoot.resolve("pom.xml"));

        // 각 도구의 wrapper 존재 여부 (실행 우선순위 판단에 사용)
        boolean gradleWrapper = Files.exists(repoRoot.resolve("gradlew"))
                || Files.exists(repoRoot.resolve("gradlew.bat"));
        boolean mavenWrapper = Files.exists(repoRoot.resolve("mvnw"))
                || Files.exists(repoRoot.resolve("mvnw.cmd"));

        return new Detected(hasGradle, gradleWrapper, hasMaven, mavenWrapper);
    }

    public record Detected(
            boolean hasGradle,
            boolean gradleWrapperExists,
            boolean hasMaven,
            boolean mavenWrapperExists
    ) {
        /**
         * 역할:
         * 최소 1개 이상의 빌드 도구 존재 여부를 반환한다.
         *
         * 책임:
         * 상위 오케스트레이터가 즉시 실패 처리할지 계속 진행할지 판단할 수 있도록 한다.
         */
        // 하나라도 있으면 빌드 도구가 존재하는 것으로 간주
        public boolean hasAnyBuildTool() {
            return hasGradle || hasMaven;
        }
    }
}
