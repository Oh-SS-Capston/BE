package com.example.ossdoc.domain.build.support;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class ProcessRunner {

    private static final int MAX_OUTPUT_LENGTH = 200_000;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    public Result run(Path workingDir, List<String> command, Duration timeout) {
        return run(workingDir, command, timeout, null);
    }

    public Result run(Path workingDir,
                      List<String> command,
                      Duration timeout,
                      Map<String, String> environmentOverrides) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            if (environmentOverrides != null && !environmentOverrides.isEmpty()) {
                pb.environment().putAll(environmentOverrides);
            }

            String renderedCommand = String.join(" ", command);
            log.info("[BUILD] Process start. cwd={}, timeoutSec={}, command={}", workingDir, timeout.toSeconds(), renderedCommand);

            Process process = pb.start();

            StringBuilder out = new StringBuilder();
            AtomicInteger lineCount = new AtomicInteger(0);
            AtomicReference<String> lastLine = new AtomicReference<>("");
            AtomicBoolean truncated = new AtomicBoolean(false);

            Thread outputReader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        lineCount.incrementAndGet();
                        lastLine.set(line);

                        if (!truncated.get()) {
                            if (out.length() + line.length() + 1 <= MAX_OUTPUT_LENGTH) {
                                out.append(line).append("\n");
                            } else {
                                truncated.set(true);
                                out.append("[OUTPUT TRUNCATED]\n");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[BUILD] Failed to read process output. command={}", renderedCommand, e);
                }
            }, "build-process-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            long startedAtNanos = System.nanoTime();
            long timeoutNanos = timeout.toNanos();
            long nextHeartbeatAtNanos = startedAtNanos + HEARTBEAT_INTERVAL.toNanos();

            while (true) {
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    break;
                }

                long now = System.nanoTime();
                long elapsedNanos = now - startedAtNanos;

                if (elapsedNanos >= timeoutNanos) {
                    process.destroyForcibly();
                    outputReader.join(2000);
                    log.warn(
                            "[BUILD] Process timeout. elapsedSec={}, lines={}, command={}",
                            TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                            lineCount.get(),
                            renderedCommand
                    );
                    return new Result(124, out.toString(), "TIMEOUT");
                }

                if (now >= nextHeartbeatAtNanos) {
                    log.info(
                            "[BUILD] Process running. elapsedSec={}, lines={}, lastLine={}",
                            TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                            lineCount.get(),
                            abbreviate(lastLine.get())
                    );
                    nextHeartbeatAtNanos += HEARTBEAT_INTERVAL.toNanos();
                }
            }

            outputReader.join(2000);
            long elapsedNanos = System.nanoTime() - startedAtNanos;
            int exitCode = process.exitValue();

            log.info(
                    "[BUILD] Process end. exitCode={}, elapsedSec={}, lines={}, command={}",
                    exitCode,
                    TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                    lineCount.get(),
                    renderedCommand
            );

            return new Result(exitCode, out.toString(), null);
        } catch (Exception e) {
            log.warn("[BUILD] Process execution failed.", e);
            return new Result(1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String abbreviate(String line) {
        if (line == null) {
            return "";
        }

        String normalized = line.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 140) {
            return normalized;
        }
        return normalized.substring(0, 140) + "...";
    }

    @Getter
    @RequiredArgsConstructor
    public static class Result {
        private final int exitCode;
        private final String output;
        private final String error;
    }
}
