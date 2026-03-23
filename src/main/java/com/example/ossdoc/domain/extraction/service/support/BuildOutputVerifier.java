package com.example.ossdoc.domain.extraction.service.support;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.build.enums.BuildToolKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BuildOutputVerifier {

    public BuildOutputVerificationResult verify(BuildManifest buildManifest) {
        List<String> warnings = new ArrayList<>();

        if (buildManifest == null) {
            warnings.add("build_manifest is null");
            return new BuildOutputVerificationResult(
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    List.copyOf(warnings)
            );
        }

        boolean manifestExists = true;

        if (isBlank(buildManifest.getRunId())) {
            warnings.add("build_manifest.runId is missing");
        }
        if (buildManifest.getDetectedAt() == null) {
            warnings.add("build_manifest.detectedAt is missing");
        }

        BuildToolKind buildTool = buildManifest.getBuildTool();
        if (buildTool == null) {
            warnings.add("build_manifest.buildTool is missing");
        } else if (buildTool == BuildToolKind.NONE) {
            warnings.add("build_manifest.buildTool is NONE");
        }

        BuildMode buildMode = buildManifest.getBuildMode();
        if (buildMode == null) {
            warnings.add("build_manifest.buildMode is missing");
        }

        List<BuildModuleManifest> modules = safeModules(buildManifest.getModules());
        if (modules.isEmpty()) {
            warnings.add("build_manifest.modules is empty");
        }

        boolean hasSourceMetadata = modules.stream().anyMatch(this::hasAnyJavaSourceMetadata);
        boolean hasDeclaredClassDirs = modules.stream().anyMatch(m -> hasTextValues(m.getClassesDirs()));
        boolean hasCompileClasspathMetadata = modules.stream().anyMatch(m -> hasTextValues(m.getCompileClasspath()));
        boolean hasRuntimeClasspathMetadata = modules.stream().anyMatch(m -> hasTextValues(m.getRuntimeClasspath()));

        if (!hasSourceMetadata) {
            warnings.add("No module contains sourceRoots or testRoots, so AST extraction cannot proceed");
        }

        if (buildMode == BuildMode.FULL && !hasDeclaredClassDirs) {
            warnings.add("BuildMode is FULL but no classesDirs metadata was recorded");
        }

        if (buildMode == BuildMode.FULL && !hasCompileClasspathMetadata) {
            warnings.add("BuildMode is FULL but compileClasspath metadata is missing");
        }

        if (buildMode != null && buildMode != BuildMode.FULL && hasDeclaredClassDirs) {
            warnings.add("classesDirs metadata exists although buildMode is not FULL; stale bytecode will not be trusted automatically");
        }

        boolean outputMetadataPresent =
                hasDeclaredClassDirs || hasCompileClasspathMetadata || hasRuntimeClasspathMetadata;

        boolean buildUsable =
                buildTool != null
                        && buildTool != BuildToolKind.NONE
                        && buildMode != null
                        && buildMode != BuildMode.FAILED
                        && hasSourceMetadata;

        return new BuildOutputVerificationResult(
                manifestExists,
                buildUsable,
                hasSourceMetadata,
                hasDeclaredClassDirs,
                hasCompileClasspathMetadata,
                outputMetadataPresent,
                List.copyOf(warnings)
        );
    }

    private boolean hasAnyJavaSourceMetadata(BuildModuleManifest module) {
        return hasTextValues(module.getSourceRoots()) || hasTextValues(module.getTestRoots());
    }

    private boolean hasTextValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        return values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    private List<BuildModuleManifest> safeModules(List<BuildModuleManifest> modules) {
        return modules == null ? List.of() : modules;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record BuildOutputVerificationResult(
            boolean manifestExists,
            boolean buildUsable,
            boolean hasSourceMetadata,
            boolean hasDeclaredClassDirs,
            boolean hasCompileClasspathMetadata,
            boolean outputMetadataPresent,
            List<String> warnings
    ) {
        public BuildOutputVerificationResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}