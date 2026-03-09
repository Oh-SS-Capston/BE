package com.example.ossdoc.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "build")
public class BuildCommandProperties {

    /**
     * mvnw / mvnw.cmd 가 없을 때 사용할 Maven 실행 명령어
     * 예: mvn
     * 예: C:\\tools\\apache-maven-3.9.13\\bin\\mvn.cmd
     */
    private String mavenCommand = "mvn";

    /**
     * gradlew / gradlew.bat 가 없을 때 사용할 Gradle 실행 명령어
     * 예: gradle
     */
    private String gradleCommand = "gradle";
}
