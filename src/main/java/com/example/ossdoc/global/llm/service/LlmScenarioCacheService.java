package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.llm.entity.LlmScenarioCache;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.example.ossdoc.global.llm.repository.LlmScenarioCacheRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * LLM 시나리오 결과를 DB에 캐시 저장한다.
 */
@Service
@RequiredArgsConstructor
public class LlmScenarioCacheService {

    private static final String SCHEMA_VERSION = "1.0";

    private final LlmScenarioCacheRepository llmScenarioCacheRepository;
    private final ObjectMapper objectMapper;

    /**
     * run 단위 시나리오 캐시를 upsert 저장한다.
     */
    @Transactional
    public LlmScenarioCache upsertScenarioCache(
            RepoRun run,
            JsonNode scenarioSpecs,
            String model,
            String promptVersion
    ) {
        try {
            String canonicalJson = objectMapper.writeValueAsString(scenarioSpecs);
            String scenarioHash = sha256(canonicalJson);
            int scenarioCount = resolveScenarioCount(scenarioSpecs);

            return llmScenarioCacheRepository.findByRun_RunId(run.getRunId())
                    .map(existing -> {
                        existing.overwrite(
                                SCHEMA_VERSION,
                                model,
                                promptVersion,
                                scenarioCount,
                                scenarioHash,
                                scenarioSpecs
                        );
                        return existing;
                    })
                    .orElseGet(() -> llmScenarioCacheRepository.save(new LlmScenarioCache(
                            null,
                            run,
                            SCHEMA_VERSION,
                            model,
                            promptVersion,
                            scenarioCount,
                            scenarioHash,
                            scenarioSpecs
                    )));
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmErrorCode.SCENARIO_CACHE_SAVE_FAILED);
        }
    }

    /**
     * scenario_specs.json의 scenarios 배열 길이를 캐시 메타로 저장한다.
     */
    private int resolveScenarioCount(JsonNode scenarioSpecs) {
        JsonNode scenarios = scenarioSpecs == null ? null : scenarioSpecs.get("scenarios");
        if (scenarios == null || !scenarios.isArray()) {
            return 0;
        }
        return scenarios.size();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new LlmException(LlmErrorCode.SCENARIO_CACHE_SAVE_FAILED);
        }
    }
}

