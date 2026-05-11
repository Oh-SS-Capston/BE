package com.example.ossdoc.domain.run.repository;

import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.entity.RunPipelineStepExecution;
import com.example.ossdoc.domain.run.enums.RunStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunPipelineStepExecutionRepository extends JpaRepository<RunPipelineStepExecution, Long> {

    Optional<RunPipelineStepExecution> findByJobAndStage(
            RunPipelineJob job,
            RunStage stage
    );

    List<RunPipelineStepExecution> findAllByRun_RunIdOrderByStepIdAsc(String runId);
}