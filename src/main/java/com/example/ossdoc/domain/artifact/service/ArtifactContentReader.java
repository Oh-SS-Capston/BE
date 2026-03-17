package com.example.ossdoc.domain.artifact.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.fasterxml.jackson.databind.JsonNode;

public interface ArtifactContentReader {
    JsonNode readJson(Artifact artifact);
}