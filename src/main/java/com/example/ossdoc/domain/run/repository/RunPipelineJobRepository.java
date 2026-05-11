package com.example.ossdoc.domain.run.repository;

import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RunPipelineJobRepository extends JpaRepository<RunPipelineJob, Long> {

    boolean existsByRun_RunId(String runId);

    Optional<RunPipelineJob> findByRun_RunId(String runId);

    /*
     * PostgreSQL SKIP LOCKED 기반 작업 선점 쿼리입니다.
     *
     * 여러 서버 인스턴스가 동시에 떠 있어도 하나의 worker만 job을 가져갑니다.
     * 작업 중 서버가 죽으면 locked_until 만료 후 다른 worker가 다시 가져갈 수 있습니다.
     */
    @Query(
            value = """
                    SELECT *
                    FROM run_pipeline_job
                    WHERE job_id IN (
                        SELECT job_id
                        FROM run_pipeline_job
                        WHERE
                            (
                                status IN ('QUEUED', 'RETRYING')
                                AND next_run_at <= CURRENT_TIMESTAMP
                            )
                            OR
                            (
                                status = 'RUNNING'
                                AND locked_until < CURRENT_TIMESTAMP
                            )
                        ORDER BY created_at ASC
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    """,
            nativeQuery = true
    )
    Optional<RunPipelineJob> findNextRunnableJobForUpdate();
}