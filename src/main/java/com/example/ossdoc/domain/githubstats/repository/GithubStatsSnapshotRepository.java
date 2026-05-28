package com.example.ossdoc.domain.githubstats.repository;

import com.example.ossdoc.domain.githubstats.entity.GithubStatsSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GithubStatsSnapshotRepository extends JpaRepository<GithubStatsSnapshot, Long> {

    @Query("""
            select s
            from GithubStatsSnapshot s
            where s.run.runId = :runId
            order by s.collectedAt desc
            """)
    List<GithubStatsSnapshot> findLatestByRunId(
            @Param("runId") String runId,
            Pageable pageable
    );
}