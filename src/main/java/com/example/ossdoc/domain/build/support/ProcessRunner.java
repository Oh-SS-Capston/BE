package com.example.ossdoc.domain.build.support;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessRunner {

    public Result run(Path workingDir, List<String> command, Duration timeout) {
        return run(workingDir, command, timeout, null);
    }

    public Result run(Path workingDir, List<String> command, Duration timeout, Map<String, String> environmentOverrides) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            if (environmentOverrides != null && !environmentOverrides.isEmpty()) {
                pb.environment().putAll(environmentOverrides);
            }

            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append("\n");
                    if (out.length() > 200_000) break; // 로그 폭주 방지
                }
            }

            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new Result(124, out.toString(), "TIMEOUT");
            }
            return new Result(p.exitValue(), out.toString(), null);
        } catch (Exception e) {
            return new Result(1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class Result {
        private final int exitCode;
        private final String output;
        private final String error;
    }
}
