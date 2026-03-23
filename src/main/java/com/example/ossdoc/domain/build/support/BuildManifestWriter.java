package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.exception.BuildException;
import com.example.ossdoc.domain.build.exception.code.BuildErrorCode;
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
public class BuildManifestWriter {

    private final ObjectMapper objectMapper;

    public JsonNode toJson(BuildManifest manifest) {
        try {
            return objectMapper.valueToTree(manifest);
        } catch (Exception e) {
            log.debug("BuildManifestWriter error={}", e);
            throw new BuildException(BuildErrorCode.BUILD_MANIFEST_WRITE_FAILED);
        }
    }

    public Path write(Path artifactsDir, JsonNode buildManifest) {
        try {
            Files.createDirectories(artifactsDir);
            Path out = artifactsDir.resolve("build_manifest.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), buildManifest);
            return out;
        } catch (Exception e) {
            log.debug("BuildManifestWriter error={}", e);
            throw new BuildException(BuildErrorCode.BUILD_MANIFEST_WRITE_FAILED);
        }
    }
}
