package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobManifestWriter {

    private final ObjectMapper objectMapper;

    public Path write(Path artifactsDir, JsonNode jobManifest) {
        try {
            Files.createDirectories(artifactsDir);
            Path out = artifactsDir.resolve("job_manifest.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), jobManifest);
            return out;
        } catch (Exception e) {
            log.debug("JobManifestWriter error={}", e.toString(), e);
            throw new RunException(RunErrorCode.JOB_MANIFEST_WRITE_FAILED);
        }
    }
}
