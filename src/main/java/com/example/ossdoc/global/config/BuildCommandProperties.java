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
     * GRADLE_USER_HOME으로 사용할 경로.
     * 상대경로면 workspaceRoot 기준으로 해석하고, 절대경로면 그대로 사용한다.
     */
    private String gradleUserHomeDir = ".gradle-home";

    /**
     * Maven local repository로 사용할 경로.
     * 상대경로면 workspaceRoot 기준으로 해석하고, 절대경로면 그대로 사용한다.
     */
    private String mavenLocalRepoDir = ".m2/repository";

    /**
     * true이면 Gradle 명령에 --no-daemon을 붙인다.
     * 기본값은 false이며, 반복 실행 성능을 위해 데몬 재사용을 허용한다.
     */
    private boolean gradleNoDaemon = false;
}
