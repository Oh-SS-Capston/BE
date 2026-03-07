package com.example.ossdoc.domain.build.dto.response;

import com.example.ossdoc.domain.build.enums.BuildMode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BuildResolveResponse {
    private String runId;
    private BuildMode buildMode;
    private String buildManifestPath; // build_manifest.json 저장 경로
}
