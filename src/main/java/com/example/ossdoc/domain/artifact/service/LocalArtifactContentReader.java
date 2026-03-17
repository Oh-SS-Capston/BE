package com.example.ossdoc.domain.artifact.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.graphstore.exception.GraphStoreException;
import com.example.ossdoc.domain.graphstore.exception.code.GraphStoreErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class LocalArtifactContentReader implements ArtifactContentReader {

    private final ObjectMapper objectMapper;

    @Override
    public JsonNode readJson(Artifact artifact) {
        try {
            Path path = Path.of(artifact.getPath());
            if (!Files.exists(path)) {
                throw new GraphStoreException(GraphStoreErrorCode.FACTS_ARTIFACT_NOT_FOUND);
            }
            return objectMapper.readTree(path.toFile());
        } catch (GraphStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphStoreException(GraphStoreErrorCode.FACTS_READ_FAILED);
        }
    }
}