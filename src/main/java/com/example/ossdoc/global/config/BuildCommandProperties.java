package com.example.ossdoc.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "build")
public class BuildCommandProperties {

    /**
     * Maven command used when mvnw/mvnw.cmd is not present in the target repository.
     */
    private String mavenCommand = "mvn";

    /**
     * Gradle command used when gradlew/gradlew.bat is not present in the target repository.
     */
    private String gradleCommand = "gradle";

    /**
     * Ordered JAVA_HOME candidates for Gradle compatibility fallback retries.
     */
    private List<String> javaHomes = new ArrayList<>();

    /**
     * Enables isolated execution directories per run workspace.
     */
    private boolean isolatedExecution = true;

    /**
     * Relative directory (from workspace root) used as GRADLE_USER_HOME.
     */
    private String gradleUserHomeDir = ".gradle-home";

    /**
     * Relative directory (from workspace root) used as Maven local repository.
     */
    private String mavenLocalRepoDir = ".m2/repository";
}
