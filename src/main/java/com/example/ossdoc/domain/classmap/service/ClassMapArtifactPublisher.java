package com.example.ossdoc.domain.classmap.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.classmap.artifact.output.ClassDiagramJson;
import com.example.ossdoc.domain.classmap.enums.ClassMapScope;
import com.example.ossdoc.domain.classmap.exception.ClassMapException;
import com.example.ossdoc.domain.classmap.exception.code.ClassMapErrorCode;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClassMapArtifactPublisher {
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public Artifact publishClassDiagram(
            RepoRun run,
            ClassDiagramJson dto,
            ClassMapScope scope,
            String subsystemId
    ) {
        try {
            JsonNode content = objectMapper.valueToTree(dto);
            return artifactService.saveJsonArtifact(
                    run,
                    ArtifactKind.CLASS_DIAGRAM_JSON,
                    "1.0",
                    resolveRelativePath(scope, subsystemId),
                    content
            );
        } catch (ClassMapException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassMapException(ClassMapErrorCode.CLASS_MAP_ARTIFACT_SAVE_FAILED);
        }
    }

    private String resolveRelativePath(ClassMapScope scope, String subsystemId) {
        if (scope == null || scope == ClassMapScope.OVERVIEW) {
            return "analysis/class_diagram.json";
        }

        return "analysis/class_diagrams/" + sanitize(subsystemId) + ".json";
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }

        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
