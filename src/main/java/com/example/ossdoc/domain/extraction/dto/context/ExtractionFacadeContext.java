package com.example.ossdoc.domain.extraction.dto.context;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.ChunkingPolicy;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightResult;
import lombok.Builder;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * facade가 extraction 전체 흐름 동안 유지하는 정규화 컨텍스트.
 *
 * facade는 runId -> 경로/manifest 로딩 -> preflight -> chunk planning -> merge -> compose/write 까지의
 * 상위 orchestration만 담당하고, 각 단계의 세부 구현은 support/extractor/composer/writer에 위임한다.
 */
@Builder
public record ExtractionFacadeContext(
        FactsExtractRequest request,
        String runId,

        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,

        Path workspaceRoot,
        Path repoRoot,
        Path artifactsRoot,
        Path buildManifestPath,

        BuildManifest buildManifest,
        ExtractionPreflightResult preflightResult,
        ChunkingPolicy chunkingPolicy,

        JobMeta jobMeta,
        BuildMeta buildMeta,

        Map<String, String> engineVersions
) {
    public ExtractionFacadeContext {
        engineVersions = engineVersions == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(engineVersions));
    }

    public boolean hasPreflight() {
        return preflightResult != null;
    }
}
