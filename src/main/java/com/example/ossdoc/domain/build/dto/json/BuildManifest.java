package com.example.ossdoc.domain.build.dto.json;

import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.build.enums.BuildToolKind;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter @Builder
@NoArgsConstructor @AllArgsConstructor
public class BuildManifest {

    private String runId;
    private OffsetDateTime detectedAt;

    private BuildToolKind buildTool;
    private boolean wrapperUsed;

    private BuildMode buildMode;

    @Builder.Default
    private List<BuildModuleManifest> modules = new ArrayList<>();

    @Builder.Default
    private List<BuildFailure> failures = new ArrayList<>();
}
