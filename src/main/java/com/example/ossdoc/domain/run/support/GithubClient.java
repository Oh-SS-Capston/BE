package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.RunErrorCode;
import com.example.ossdoc.domain.run.exception.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class GithubClient {

    private final ObjectMapper objectMapper;

    // GitHub API 호출용 HTTP 클라이언트
    // GitHub API 호출용 HTTP 클라이언트
    private final WebClient webClient = WebClient.builder()
            .defaultHeader(HttpHeaders.USER_AGENT, "ossdoc")
            .build();

    // 저장소의 기본 브랜치가 뭔지 알아냄
    public String resolveDefaultBranch(String owner, String repo) {
        try {
            String json = webClient.get()
                    .uri("https://api.github.com/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .bodyToMono(String.class)   // ✅ String으로 받기
                    .block();

            if (json == null || json.isBlank()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }

            JsonNode node = objectMapper.readTree(json);
            JsonNode branch = node.get("default_branch");
            if (branch == null || branch.isNull()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }
            return branch.asText();
        } catch (Exception e) {
            throw new RunException(RunErrorCode.GITHUB_API_FAILED, e);
        }
    }

    // ref(브랜치/태그/커밋)를 실제 commit SHA로 변환하는 메서드
    public String resolveCommitSha(String owner, String repo, String ref) {
        try {
            String json = webClient.get()
                    .uri("https://api.github.com/repos/{owner}/{repo}/commits/{ref}", owner, repo, ref)
                    .retrieve()
                    .bodyToMono(String.class)   // ✅ 여기도 String으로 받기 (중요)
                    .block();

            if (json == null || json.isBlank()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }

            JsonNode node = objectMapper.readTree(json);
            JsonNode sha = node.get("sha");
            if (sha == null || sha.isNull()) {
                throw new RunException(RunErrorCode.GITHUB_API_FAILED);
            }
            return sha.asText();
        } catch (Exception e) {
            throw new RunException(RunErrorCode.GITHUB_API_FAILED, e);
        }
    }

    // 특정 ref 상태의 저장소를 ZIP으로 다운로드
    public Path downloadZip(String owner, String repo, String ref, Path targetZip) {
        try {
            byte[] bytes = webClient.get()
                    .uri("https://codeload.github.com/{owner}/{repo}/zip/{ref}", owner, repo, ref)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();

            if (bytes == null || bytes.length == 0) {
                throw new RunException(RunErrorCode.DOWNLOAD_FAILED);
            }

            Files.createDirectories(targetZip.getParent());
            Files.write(targetZip, bytes);
            return targetZip;
        } catch (Exception e) {
            throw new RunException(RunErrorCode.DOWNLOAD_FAILED, e);
        }
    }
}