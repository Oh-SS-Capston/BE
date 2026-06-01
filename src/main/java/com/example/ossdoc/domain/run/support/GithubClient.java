package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubClient {

    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ZIP_TIMEOUT = Duration.ofSeconds(120);
    private static final int ERROR_BODY_PREVIEW_LEN = 300;

    // commits/{ref} 응답은 대형 머지 커밋이면 변경 파일 목록·patch까지 포함돼 기본 256KB 한도를 넘는다.
    // SHA만 필요하지만 응답 전체를 버퍼링하므로 한도를 넉넉히(16MB) 올려 DataBufferLimitException을 막는다.
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    private final WebClient webClient = WebClient.builder()
            .defaultHeader(HttpHeaders.USER_AGENT, "ossdoc")
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
            .build();

    public String resolveDefaultBranch(String owner, String repo) {
        try {
            String json = webClient.get()
                    .uri("https://api.github.com/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(API_TIMEOUT);

            if (json == null || json.isBlank()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }

            JsonNode node = objectMapper.readTree(json);
            JsonNode branch = node.get("default_branch");
            if (branch == null || branch.isNull()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }
            return branch.asText();
        } catch (WebClientResponseException e) {
            log.error(
                    "GitHub default-branch API failed owner={}, repo={}, status={}, body={}",
                    owner,
                    repo,
                    e.getStatusCode().value(),
                    previewBody(e.getResponseBodyAsString()),
                    e
            );
            throw new RunException(RunErrorCode.GITHUB_API_FAILED);
        } catch (Exception e) {
            log.error(
                    "GitHub default-branch API failed owner={}, repo={}, cause={}",
                    owner,
                    repo,
                    e.toString(),
                    e
            );
            throw new RunException(RunErrorCode.GITHUB_API_FAILED);
        }
    }

    public String resolveCommitSha(String owner, String repo, String ref) {
        try {
            String json = webClient.get()
                    .uri("https://api.github.com/repos/{owner}/{repo}/commits/{ref}", owner, repo, ref)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(API_TIMEOUT);

            if (json == null || json.isBlank()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }

            JsonNode node = objectMapper.readTree(json);
            JsonNode sha = node.get("sha");
            if (sha == null || sha.isNull()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }
            return sha.asText();
        } catch (WebClientResponseException e) {
            log.error(
                    "GitHub commit API failed owner={}, repo={}, ref={}, status={}, body={}",
                    owner,
                    repo,
                    ref,
                    e.getStatusCode().value(),
                    previewBody(e.getResponseBodyAsString()),
                    e
            );
            throw new RunException(RunErrorCode.GITHUB_API_FAILED);
        } catch (Exception e) {
            log.error(
                    "GitHub commit API failed owner={}, repo={}, ref={}, cause={}",
                    owner,
                    repo,
                    ref,
                    e.toString(),
                    e
            );
            throw new RunException(RunErrorCode.GITHUB_API_FAILED);
        }
    }

    public Path downloadZip(String owner, String repo, String commitSha, Path targetZip) {
        ensureParentDir(targetZip);

        boolean downloaded = tryDownloadZipToFile(
                owner,
                repo,
                commitSha,
                "codeload",
                "https://codeload.github.com/{owner}/{repo}/zip/{commitSha}",
                targetZip
        );

        if (!downloaded) {
            log.warn(
                    "Primary zip download failed owner={}, repo={}, sha={}. Trying GitHub API zipball fallback.",
                    owner,
                    repo,
                    abbreviateSha(commitSha)
            );
            downloaded = tryDownloadZipToFile(
                    owner,
                    repo,
                    commitSha,
                    "api-zipball",
                    "https://api.github.com/repos/{owner}/{repo}/zipball/{commitSha}",
                    targetZip
            );
        }

        if (!downloaded) {
            throw new RunException(RunErrorCode.DOWNLOAD_FAILED);
        }
        return targetZip;
    }

    private boolean tryDownloadZipToFile(
            String owner,
            String repo,
            String commitSha,
            String source,
            String uriTemplate,
            Path targetZip
    ) {
        try {
            Files.deleteIfExists(targetZip);

            DataBufferUtils.write(
                    webClient.get()
                            .uri(uriTemplate, owner, repo, commitSha)
                            .retrieve()
                            .bodyToFlux(DataBuffer.class),
                    targetZip,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ).block(ZIP_TIMEOUT);

            if (!Files.exists(targetZip)) {
                log.warn(
                        "Zip file not created source={}, owner={}, repo={}, sha={}",
                        source,
                        owner,
                        repo,
                        abbreviateSha(commitSha)
                );
                return false;
            }

            long size = Files.size(targetZip);
            if (size <= 0) {
                log.warn(
                        "Zip file is empty source={}, owner={}, repo={}, sha={}",
                        source,
                        owner,
                        repo,
                        abbreviateSha(commitSha)
                );
                Files.deleteIfExists(targetZip);
                return false;
            }
            return true;
        } catch (WebClientResponseException e) {
            log.error(
                    "Zip download failed source={}, owner={}, repo={}, sha={}, status={}, body={}",
                    source,
                    owner,
                    repo,
                    abbreviateSha(commitSha),
                    e.getStatusCode().value(),
                    previewBody(e.getResponseBodyAsString()),
                    e
            );
            deleteQuietly(targetZip);
            return false;
        } catch (Exception e) {
            log.error(
                    "Zip download failed source={}, owner={}, repo={}, sha={}, cause={}",
                    source,
                    owner,
                    repo,
                    abbreviateSha(commitSha),
                    e.toString(),
                    e
            );
            deleteQuietly(targetZip);
            return false;
        }
    }

    private void ensureParentDir(Path targetZip) {
        try {
            Path parent = targetZip.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            log.error("Failed to create parent directory for zip path={}, cause={}", targetZip, e.toString(), e);
            throw new RunException(RunErrorCode.DOWNLOAD_FAILED);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private String previewBody(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        return body.length() > ERROR_BODY_PREVIEW_LEN ? body.substring(0, ERROR_BODY_PREVIEW_LEN) + "..." : body;
    }

    private String abbreviateSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "<empty>";
        }
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }
}
