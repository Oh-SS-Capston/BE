package com.example.ossdoc.domain.run.repository;

import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.entity.RunPipelineStepExecution;
import com.example.ossdoc.domain.run.enums.RunStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RunPipelineStepExecutionRepository extends JpaRepository<RunPipelineStepExecution, Long> {

    @Query("""
            SELECT s
            FROM RunPipelineStepExecution s
            WHERE s.job = :job
              AND s.stage = :stage
            """)
    Optional<RunPipelineStepExecution> findStepByJobAndStage(
            @Param("job") RunPipelineJob job,
            @Param("stage") RunStage stage
    );

    @Query("""
            SELECT s
            FROM RunPipelineStepExecution s
            WHERE s.run.runId = :runId
            ORDER BY s.stepId ASC
            """)
    List<RunPipelineStepExecution> findStepsByRunId(@Param("runId") String runId);
}
