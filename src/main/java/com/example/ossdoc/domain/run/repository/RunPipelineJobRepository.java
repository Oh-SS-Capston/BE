package com.example.ossdoc.domain.run.repository;

import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.enums.PipelineJobStatus;
import com.example.ossdoc.domain.run.enums.RunStage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RunPipelineJobRepository extends JpaRepository<RunPipelineJob, Long> {

    @Query("""
            SELECT CASE WHEN COUNT(j) > 0 THEN true ELSE false END
            FROM RunPipelineJob j
            WHERE j.run.runId = :runId
            """)
    boolean existsByRunId(@Param("runId") String runId);

    @Query("""
            SELECT j
            FROM RunPipelineJob j
            WHERE j.run.runId = :runId
            """)
    Optional<RunPipelineJob> findJobByRunId(@Param("runId") String runId);

    /**
     * 동일 저장소/커밋 기준으로 현재 진행 가능한(또는 진행 중인) 작업을 조회합니다.
     * W09 락 경합 시 기존 실행을 attach할 대상 탐색에 사용합니다.
     */
    @Query("""
            SELECT j
            FROM RunPipelineJob j
            WHERE j.run.repoOwner = :repoOwner
              AND j.run.repoName = :repoName
              AND j.run.commitSha = :commitSha
              AND j.status IN :statuses
            ORDER BY j.createdAt ASC
            """)
    List<RunPipelineJob> findActiveJobsByRepoAndSha(
            @Param("repoOwner") String repoOwner,
            @Param("repoName") String repoName,
            @Param("commitSha") String commitSha,
            @Param("statuses") Collection<PipelineJobStatus> statuses,
            Pageable pageable
    );

    /**
     * 캐시 대기 중인 job만 조회합니다.
     * - RUNNING + QUEUED stage + lock 비어있음 + 메시지 prefix로 식별
     */
    @Query("""
            SELECT j
            FROM RunPipelineJob j
            WHERE j.status = :status
              AND j.currentStage = :stage
              AND j.statusMessage LIKE CONCAT(:statusMessagePrefix, '%')
              AND j.lockedBy IS NULL
            ORDER BY j.createdAt ASC
            """)
    List<RunPipelineJob> findCacheWaitingJobs(
            @Param("status") PipelineJobStatus status,
            @Param("stage") RunStage stage,
            @Param("statusMessagePrefix") String statusMessagePrefix,
            Pageable pageable
    );

    /**
     * 동일 repo/sha에서 "내 run이 아닌" 활성 source job 1건을 찾습니다.
     */
    @Query("""
            SELECT j
            FROM RunPipelineJob j
            WHERE j.run.repoOwner = :repoOwner
              AND j.run.repoName = :repoName
              AND j.run.commitSha = :commitSha
              AND j.run.runId <> :excludeRunId
              AND j.status IN :statuses
            ORDER BY j.createdAt ASC
            """)
    List<RunPipelineJob> findActiveJobsByRepoAndShaExcludingRun(
            @Param("repoOwner") String repoOwner,
            @Param("repoName") String repoName,
            @Param("commitSha") String commitSha,
            @Param("excludeRunId") String excludeRunId,
            @Param("statuses") Collection<PipelineJobStatus> statuses,
            Pageable pageable
    );

    /**
     * 동일 repo/sha에서 "내 run이 아닌" 최근 완료 source job 1건을 찾습니다.
     */
    @Query("""
            SELECT j
            FROM RunPipelineJob j
            WHERE j.run.repoOwner = :repoOwner
              AND j.run.repoName = :repoName
              AND j.run.commitSha = :commitSha
              AND j.run.runId <> :excludeRunId
              AND j.status IN :statuses
            ORDER BY j.createdAt DESC
            """)
    List<RunPipelineJob> findFinishedJobsByRepoAndShaExcludingRun(
            @Param("repoOwner") String repoOwner,
            @Param("repoName") String repoName,
            @Param("commitSha") String commitSha,
            @Param("excludeRunId") String excludeRunId,
            @Param("statuses") Collection<PipelineJobStatus> statuses,
            Pageable pageable
    );

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
