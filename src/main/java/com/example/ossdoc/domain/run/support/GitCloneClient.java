package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.global.properties.RunSnapshotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GitHub repository를 commit SHA로 고정된 shallow clone 형태로 가져온다.
 *
 * 전략:
 *   1) targetDir에 git init
 *   2) origin remote 등록 (HTTPS)
 *   3) git fetch --depth 1 origin {sha}
 *   4) git checkout FETCH_HEAD
 *
 * 결과 디렉터리 구조는 ZIP 추출과 달리 `targetDir` 바로 아래가 repo 루트가 된다.
 * 다운스트림은 RepoRootResolver가 두 형태 모두 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitCloneClient {

    private final RunSnapshotProperties properties;

    /**
     * targetDir에 shallow clone을 수행하고 commit SHA로 체크아웃한다.
     *
     * @param owner       GitHub 소유자
     * @param repo        GitHub 저장소 이름
     * @param commitSha   고정할 commit SHA (full 40자 권장)
     * @param targetDir   clone 대상 디렉터리 (없으면 생성)
     * @return targetDir (절대 경로)
     */
    public Path cloneAtCommit(String owner, String repo, String commitSha, Path targetDir) {
        ensureFreshDir(targetDir);

        String git = properties.getGitCommand();
        String remoteUrl = String.format("https://github.com/%s/%s.git", owner, repo);

        try {
            runGit(targetDir, List.of(git, "init", "-q"));
            runGit(targetDir, List.of(git, "remote", "add", "origin", remoteUrl));
            runGit(targetDir, List.of(git, "fetch", "--depth", "1", "origin", commitSha));
            runGit(targetDir, List.of(git, "checkout", "-q", "FETCH_HEAD"));

            log.info(
                    "[SNAPSHOT] git clone success owner={}, repo={}, sha={}, targetDir={}",
                    owner,
                    repo,
                    abbreviateSha(commitSha),
                    targetDir
            );
            return targetDir;

        } catch (RunException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "[SNAPSHOT] git clone failed owner={}, repo={}, sha={}, cause={}",
                    owner,
                    repo,
                    abbreviateSha(commitSha),
                    e.toString(),
                    e
            );
            throw new RunException(RunErrorCode.CLONE_FAILED);
        }
    }

    private void ensureFreshDir(Path targetDir) {
        try {
            if (Files.exists(targetDir)) {
                // 재시도 안전성을 위해 비어 있지 않으면 정리한다.
                deleteRecursively(targetDir);
            }
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            log.error("[SNAPSHOT] Failed to prepare clone target dir path={}, cause={}", targetDir, e.toString(), e);
            throw new RunException(RunErrorCode.CLONE_FAILED);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private void runGit(Path workingDir, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);

        // git이 대화형 인증 프롬프트를 띄우지 못하게 막는다 (공개 repo이므로 자격증명 불필요).
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("[SNAPSHOT] git process start failed command={}, cause={}", command, e.toString(), e);
            throw new RunException(RunErrorCode.CLONE_FAILED);
        }

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            process.destroyForcibly();
            log.error("[SNAPSHOT] git process read failed command={}, cause={}", command, e.toString(), e);
            throw new RunException(RunErrorCode.CLONE_FAILED);
        }

        boolean finished;
        try {
            finished = process.waitFor(properties.getGitTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            log.error("[SNAPSHOT] git process interrupted command={}", command, e);
            throw new RunException(RunErrorCode.CLONE_FAILED);
        }

        if (!finished) {
            process.destroyForcibly();
            log.error("[SNAPSHOT] git timeout command={}, timeoutSec={}", command, properties.getGitTimeoutSeconds());
            throw new RunException(RunErrorCode.CLONE_FAILED);
        }

        int exit = process.exitValue();
        if (exit != 0) {
            log.error(
                    "[SNAPSHOT] git command failed exitCode={}, command={}, output={}",
                    exit,
                    command,
                    abbreviate(output, 400)
            );
            throw new RunException(RunErrorCode.CLONE_FAILED);
        }

        if (log.isDebugEnabled() && !output.isBlank()) {
            log.debug("[SNAPSHOT] git command success command={}, output={}", command, abbreviate(output, 200));
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String abbreviateSha(String sha) {
        if (sha == null || sha.isBlank()) return "<empty>";
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }
}
