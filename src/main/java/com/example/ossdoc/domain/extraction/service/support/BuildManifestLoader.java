package com.example.ossdoc.domain.extraction.service.support;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class BuildManifestLoader {

    private final ObjectMapper objectMapper;

    /**
     * 로컬 artifacts 디렉터리에서 build_manifest.json을 읽어 BuildManifest로 반환.
     * 파일이 없거나 역직렬화 실패 시 null 반환 (warning은 호출부에서 추가).
     */
    public BuildManifest load(Path artifactsDir) {
        Path file = artifactsDir.resolve("build_manifest.json");
        if (!Files.exists(file)) {
            log.warn("build_manifest.json not found — path: {}", file);
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), BuildManifest.class);
        } catch (Exception e) {
            log.warn("build_manifest.json 로드 실패 — path: {}, 원인: {}", file, e.getMessage());
            return null;
        }
    }
}
