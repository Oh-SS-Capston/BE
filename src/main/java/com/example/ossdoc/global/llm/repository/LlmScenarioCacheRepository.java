package com.example.ossdoc.global.llm.repository;

import com.example.ossdoc.global.llm.entity.LlmScenarioCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmScenarioCacheRepository extends JpaRepository<LlmScenarioCache, Long> {

    /**
     * run 단위로 저장된 시나리오 캐시를 조회한다.
     */
    Optional<LlmScenarioCache> findByRun_RunId(String runId);
}

