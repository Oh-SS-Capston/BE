package com.example.ossdoc.domain.extraction.service.writer;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;
import com.example.ossdoc.domain.extraction.exception.ExtractionErrorCode;
import com.example.ossdoc.domain.extraction.exception.ExtractionException;
import com.example.ossdoc.domain.extraction.service.support.FactsSchema;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * writer 기본 구현
 *
 * 책임:
 * - facts.json S3 업로드
 * - FactsExtractResponse 생성
 */
@Slf4j
@Component
public class DefaultFactsWriter implements FactsWriter {

    private final FactsResponseFactory responseFactory;
    private final ObjectMapper objectMapper;
    private final RepoRunRepository repoRunRepository;
    private final ArtifactService artifactService;

    public DefaultFactsWriter(
            FactsResponseFactory responseFactory,
            ObjectMapper objectMapper,
            RepoRunRepository repoRunRepository,
            ArtifactService artifactService
    ) {
        this.responseFactory = Objects.requireNonNull(responseFactory, "responseFactory must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.repoRunRepository = Objects.requireNonNull(repoRunRepository, "repoRunRepository must not be null");
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService must not be null");
    }

    @Override
    public FactsExtractResponse writeAndBuildResponse(FactsWriteContext context) {
        Objects.requireNonNull(context, "context must not be null");
        validateDocument(context.document());
        saveToLocal(context);
        uploadToS3(context);
        return responseFactory.create(context);
    }

    private void validateDocument(FactsDocument document) {
        Objects.requireNonNull(document, "document must not be null");

        if (document.schemaVersion() == null || document.schemaVersion().isBlank()) {
            throw new IllegalArgumentException("FactsDocument.schemaVersion must not be blank");
        }
        if (document.job() == null) {
            throw new IllegalArgumentException("FactsDocument.job must not be null");
        }
        if (document.build() == null) {
            throw new IllegalArgumentException("FactsDocument.build must not be null");
        }
        if (document.extraction() == null) {
            throw new IllegalArgumentException("FactsDocument.extraction must not be null");
        }
        if (document.stats() == null) {
            throw new IllegalArgumentException("FactsDocument.stats must not be null");
        }
        if (document.evidence() == null) {
            throw new IllegalArgumentException("FactsDocument.evidence must not be null");
        }
        if (document.symbols() == null) {
            throw new IllegalArgumentException("FactsDocument.symbols must not be null");
        }
        if (document.relations() == null) {
            throw new IllegalArgumentException("FactsDocument.relations must not be null");
        }
        if (document.observations() == null) {
            throw new IllegalArgumentException("FactsDocument.observations must not be null");
        }
    }

    private void saveToLocal(FactsWriteContext context) {
        Path artifactsDir = context.artifactsRoot();
        try {
            Files.createDirectories(artifactsDir);
            Path out = artifactsDir.resolve(FactsSchema.FACTS_FILE_NAME);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(out.toFile(), context.document());
            log.info("[EXTRACTION] facts.json 로컬 저장 완료: {}", out);
        } catch (Exception e) {
            log.warn("[EXTRACTION] facts.json 로컬 저장 실패 (S3 업로드는 계속 진행): {}", e.getMessage());
        }
    }

    private void uploadToS3(FactsWriteContext context) {
        RepoRun run = repoRunRepository.findById(context.runId())
                .orElseThrow(() -> new ExtractionException(ExtractionErrorCode.RUN_NOT_FOUND));
        JsonNode jsonNode = objectMapper.valueToTree(context.document());
        artifactService.saveJsonArtifact(
                run,
                ArtifactKind.FACTS_JSON,
                resolveSchemaVersion(context.document()),
                FactsSchema.FACTS_FILE_NAME,
                jsonNode
        );
    }

    private String resolveSchemaVersion(FactsDocument document) {
        String v = document.schemaVersion();
        return (v == null || v.isBlank()) ? FactsSchema.SCHEMA_VERSION : v;
    }
}