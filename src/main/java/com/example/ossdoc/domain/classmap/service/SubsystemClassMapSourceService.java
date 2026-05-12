package com.example.ossdoc.domain.classmap.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.classmap.exception.ClassMapException;
import com.example.ossdoc.domain.classmap.exception.code.ClassMapErrorCode;
import com.example.ossdoc.domain.cluster.artifact.output.SubsystemsJson;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubsystemClassMapSourceService {

    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public Subsystem findRequiredSubsystem(String runId, String subsystemId) {
        if (subsystemId == null || subsystemId.isBlank()) {
            throw new ClassMapException(ClassMapErrorCode.CLASS_MAP_SUBSYSTEM_ID_REQUIRED);
        }

        Artifact artifact = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.SUBSYSTEMS_JSON)
                .orElseThrow(() -> new ClassMapException(ClassMapErrorCode.CLASS_MAP_SUBSYSTEMS_NOT_READY));

        try {
            SubsystemsJson subsystemsJson = objectMapper.treeToValue(artifact.getMeta(), SubsystemsJson.class);
            if (subsystemsJson.getSubsystems() == null) {
                throw new ClassMapException(ClassMapErrorCode.CLASS_MAP_SUBSYSTEM_NOT_FOUND);
            }

            return subsystemsJson.getSubsystems().stream()
                    .filter(subsystem -> subsystemId.equals(subsystem.getSubsystemId()))
                    .findFirst()
                    .orElseThrow(() -> new ClassMapException(ClassMapErrorCode.CLASS_MAP_SUBSYSTEM_NOT_FOUND));
        } catch (ClassMapException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassMapException(ClassMapErrorCode.CLASS_MAP_SUBSYSTEMS_NOT_READY);
        }
    }
}
