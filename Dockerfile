FROM eclipse-temurin:11-jdk AS jdk11
FROM eclipse-temurin:17-jdk AS jdk17

FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src

RUN chmod +x ./gradlew && ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jdk

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        gradle \
        maven \
        unzip \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash ossdoc \
    && mkdir -p /workspace \
    && chown -R ossdoc:ossdoc /app /workspace

COPY --from=jdk11 /opt/java/openjdk /opt/jdks/11
COPY --from=jdk17 /opt/java/openjdk /opt/jdks/17

COPY --from=builder /app/build/libs/*.jar /app/app.jar

ENV JAVA11_HOME=/opt/jdks/11 \
    JAVA17_HOME=/opt/jdks/17 \
    JAVA21_HOME=/opt/java/openjdk \
    OSSDOC_WORKSPACE_BASE_DIR=/workspace \
    MAVEN_COMMAND=/usr/bin/mvn \
    GRADLE_COMMAND=/usr/bin/gradle

EXPOSE 8080

USER ossdoc

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
